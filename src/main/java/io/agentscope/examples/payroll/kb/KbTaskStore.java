package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import io.agentscope.examples.payroll.kb.KbModels.IngestTask;
import io.agentscope.examples.payroll.kb.KbModels.TaskLog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** kb_ingest_task 与 kb_task_log 的 JDBC 存储。 */
@Repository
public class KbTaskStore {

    private static final String SELECT =
            "SELECT task_id, series_id, document_id, task_type, task_status, progress, stage,"
                    + " retry_count, retry_of_task_id, config_version_id, idempotency_key, payload,"
                    + " failed_items, error_code, error_message, operator_id, started_at, finished_at,"
                    + " created_at, updated_at FROM kb_ingest_task";

    private final PayrollDatabase database;

    public KbTaskStore(PayrollDatabase database) {
        this.database = database;
    }

    public IngestTask insert(IngestTask draft) {
        String sql =
                """
                INSERT INTO kb_ingest_task
                    (series_id, document_id, task_type, task_status, progress, stage, retry_count,
                     retry_of_task_id, config_version_id, idempotency_key, payload, operator_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, draft.seriesId());
            if (draft.documentId() == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, draft.documentId());
            }
            statement.setString(3, draft.type().name());
            statement.setString(4, draft.status().name());
            statement.setInt(5, draft.progress());
            text(statement, 6, draft.stage());
            statement.setInt(7, draft.retryCount());
            number(statement, 8, draft.retryOfTaskId());
            number(statement, 9, draft.configVersionId());
            statement.setString(10, draft.idempotencyKey());
            text(statement, 11, draft.payload());
            statement.setLong(12, draft.operatorId());
            statement.setTimestamp(13, now);
            statement.setTimestamp(14, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建入库任务失败：" + e.getMessage(), e);
        }
    }

    public Optional<IngestTask> findById(long taskId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE task_id = ?")) {
            statement.setLong(1, taskId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入库任务失败：" + e.getMessage(), e);
        }
    }

    public List<IngestTask> findBySeries(
            long seriesId, TaskStatus status, TaskType type, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE series_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(seriesId);
        if (status != null) {
            sql.append(" AND task_status = ?");
            params.add(status.name());
        }
        if (type != null) {
            sql.append(" AND task_type = ?");
            params.add(type.name());
        }
        sql.append(" ORDER BY created_at DESC, task_id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 500)));
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<IngestTask> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入库任务失败：" + e.getMessage(), e);
        }
    }

    public List<IngestTask> findByDocument(long documentId, int limit) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE document_id = ? ORDER BY created_at DESC LIMIT ?")) {
            statement.setLong(1, documentId);
            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = statement.executeQuery()) {
                List<IngestTask> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入库任务失败：" + e.getMessage(), e);
        }
    }

    public Optional<IngestTask> findLatestByDocument(long documentId) {
        List<IngestTask> tasks = findByDocument(documentId, 1);
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    public boolean hasActiveTask(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM kb_ingest_task WHERE document_id = ?"
                                        + " AND task_status IN ('QUEUED','RUNNING','CANCELING')")) {
            statement.setLong(1, documentId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验入库任务失败：" + e.getMessage(), e);
        }
    }

    public int markRunning(long taskId, String stage, int progress) {
        return update(
                taskId,
                "UPDATE kb_ingest_task SET task_status = 'RUNNING', stage = ?, progress = ?,"
                        + " started_at = COALESCE(started_at, ?), updated_at = ?"
                        + " WHERE task_id = ? AND task_status = 'QUEUED'",
                statement -> {
                    statement.setString(1, stage);
                    statement.setInt(2, progress);
                    statement.setTimestamp(3, Timestamp.from(Instant.now()));
                    statement.setTimestamp(4, Timestamp.from(Instant.now()));
                    statement.setLong(5, taskId);
                });
    }

    public void updateProgress(long taskId, String stage, int progress) {
        update(
                taskId,
                "UPDATE kb_ingest_task SET stage = ?, progress = ?, updated_at = ? WHERE task_id = ?",
                statement -> {
                    statement.setString(1, stage);
                    statement.setInt(2, progress);
                    statement.setTimestamp(3, Timestamp.from(Instant.now()));
                    statement.setLong(4, taskId);
                });
    }

    public void markSucceeded(long taskId) {
        update(
                taskId,
                "UPDATE kb_ingest_task SET task_status = 'SUCCEEDED', progress = 100, stage = 'DONE',"
                        + " finished_at = ?, updated_at = ?, error_code = NULL, error_message = NULL"
                        + " WHERE task_id = ?",
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(Instant.now()));
                    statement.setTimestamp(2, Timestamp.from(Instant.now()));
                    statement.setLong(3, taskId);
                });
    }

    public void markFailed(long taskId, String errorCode, String errorMessage, String failedItems) {
        update(
                taskId,
                "UPDATE kb_ingest_task SET task_status = 'FAILED', error_code = ?, error_message = ?,"
                        + " failed_items = ?, finished_at = ?, updated_at = ? WHERE task_id = ?",
                statement -> {
                    statement.setString(1, errorCode == null ? "INGEST_FAILED" : errorCode);
                    statement.setString(2, truncate(errorMessage, 500));
                    text(statement, 3, failedItems);
                    statement.setTimestamp(4, Timestamp.from(Instant.now()));
                    statement.setTimestamp(5, Timestamp.from(Instant.now()));
                    statement.setLong(6, taskId);
                });
    }

    public void markCanceled(long taskId) {
        update(
                taskId,
                "UPDATE kb_ingest_task SET task_status = 'CANCELED', finished_at = ?, updated_at = ?"
                        + " WHERE task_id = ?",
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(Instant.now()));
                    statement.setTimestamp(2, Timestamp.from(Instant.now()));
                    statement.setLong(3, taskId);
                });
    }

    public int requestCancel(long taskId) {
        return update(
                taskId,
                "UPDATE kb_ingest_task SET task_status = 'CANCELING', updated_at = ?"
                        + " WHERE task_id = ? AND task_status IN ('QUEUED','RUNNING')",
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(Instant.now()));
                    statement.setLong(2, taskId);
                });
    }

    public boolean cancelRequested(long taskId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT task_status FROM kb_ingest_task WHERE task_id = ?")) {
            statement.setLong(1, taskId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                String status = rs.getString(1);
                return "CANCELING".equals(status) || "CANCELED".equals(status);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入库任务失败：" + e.getMessage(), e);
        }
    }

    public void appendLog(long taskId, String stage, String level, String message, Integer costMs) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO kb_task_log (task_id, stage, log_level, message, cost_ms,"
                                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, taskId);
            statement.setString(2, stage);
            statement.setString(3, level == null ? "INFO" : level);
            statement.setString(4, truncate(message, 1000));
            if (costMs == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setInt(5, costMs);
            }
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入任务日志失败：" + e.getMessage(), e);
        }
    }

    public List<TaskLog> findLogs(long taskId, int limit) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT log_id, task_id, stage, log_level, message, cost_ms, created_at"
                                        + " FROM kb_task_log WHERE task_id = ? ORDER BY created_at, log_id"
                                        + " LIMIT ?")) {
            statement.setLong(1, taskId);
            statement.setInt(2, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rs = statement.executeQuery()) {
                List<TaskLog> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(
                            new TaskLog(
                                    rs.getLong("log_id"),
                                    rs.getLong("task_id"),
                                    rs.getString("stage"),
                                    rs.getString("log_level"),
                                    rs.getString("message"),
                                    KbSeriesStore.intOrNull(rs, "cost_ms"),
                                    KbSeriesStore.instant(rs, "created_at")));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询任务日志失败：" + e.getMessage(), e);
        }
    }

    public void deleteBySeries(long seriesId) {
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM kb_task_log WHERE task_id IN"
                                    + " (SELECT task_id FROM kb_ingest_task WHERE series_id = ?)")) {
                statement.setLong(1, seriesId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_ingest_task WHERE series_id = ?")) {
                statement.setLong(1, seriesId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清理入库任务失败：" + e.getMessage(), e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private int update(long taskId, String sql, Binder binder) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新入库任务失败：" + e.getMessage(), e);
        }
    }

    private static void number(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void text(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private IngestTask map(ResultSet rs) throws SQLException {
        return new IngestTask(
                rs.getLong("task_id"),
                rs.getLong("series_id"),
                KbSeriesStore.longOrNull(rs, "document_id"),
                TaskType.parse(rs.getString("task_type"), TaskType.INGEST),
                TaskStatus.parse(rs.getString("task_status"), TaskStatus.QUEUED),
                rs.getInt("progress"),
                rs.getString("stage"),
                rs.getInt("retry_count"),
                KbSeriesStore.longOrNull(rs, "retry_of_task_id"),
                KbSeriesStore.longOrNull(rs, "config_version_id"),
                rs.getString("idempotency_key"),
                rs.getString("payload"),
                rs.getString("failed_items"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getLong("operator_id"),
                KbSeriesStore.instant(rs, "started_at"),
                KbSeriesStore.instant(rs, "finished_at"),
                KbSeriesStore.instant(rs, "created_at"),
                KbSeriesStore.instant(rs, "updated_at"));
    }
}
