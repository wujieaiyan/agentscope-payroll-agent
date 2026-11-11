package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.SeriesStatus;
import io.agentscope.examples.payroll.kb.KbModels.Series;
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

/** kb_series 与 kb_member 的 JDBC 存储。 */
@Repository
public class KbSeriesStore {

    private static final String SELECT =
            "SELECT series_id, name, description, dept_id, author_id, index_strategy, status,"
                    + " active_config_version_id, doc_count, ready_doc_count, failed_doc_count,"
                    + " chunk_count, row_version, created_at, updated_at, deleted_at FROM kb_series";

    private final PayrollDatabase database;

    public KbSeriesStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Series> findAll() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE deleted_at IS NULL ORDER BY updated_at DESC");
                ResultSet rs = statement.executeQuery()) {
            List<Series> result = new ArrayList<>();
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库失败：" + e.getMessage(), e);
        }
    }

    public Optional<Series> findById(long id, boolean includeDeleted) {
        String sql = SELECT + " WHERE series_id = ?" + (includeDeleted ? "" : " AND deleted_at IS NULL");
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库失败：" + e.getMessage(), e);
        }
    }

    public Series insert(Series series) {
        String sql =
                """
                INSERT INTO kb_series
                    (name, description, dept_id, author_id, index_strategy, status,
                     active_config_version_id, row_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """;
        Instant now = Instant.now();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, series.name());
            statement.setString(2, series.description() == null ? "" : series.description());
            statement.setLong(3, series.deptId());
            statement.setLong(4, series.authorId());
            statement.setString(5, series.indexStrategy().name());
            statement.setString(6, series.status().name());
            if (series.activeConfigVersionId() == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, series.activeConfigVersionId());
            }
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                return findById(id, true).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建知识库失败：" + e.getMessage(), e);
        }
    }

    public void update(Series series, boolean bumpVersion) {
        String sql =
                "UPDATE kb_series SET name = ?, description = ?, dept_id = ?, index_strategy = ?,"
                        + " status = ?, active_config_version_id = ?, updated_at = ?"
                        + (bumpVersion ? ", row_version = row_version + 1" : "")
                        + " WHERE series_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, series.name());
            statement.setString(2, series.description() == null ? "" : series.description());
            statement.setLong(3, series.deptId());
            statement.setString(4, series.indexStrategy().name());
            statement.setString(5, series.status().name());
            if (series.activeConfigVersionId() == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, series.activeConfigVersionId());
            }
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.setLong(8, series.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新知识库失败：" + e.getMessage(), e);
        }
    }

    public void updateStatus(long id, SeriesStatus status) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_series SET status = ?, updated_at = ? WHERE series_id = ?")) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新知识库状态失败：" + e.getMessage(), e);
        }
    }

    public void softDelete(long id) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_series SET status = 'DELETED', deleted_at = ?,"
                                        + " updated_at = ?, row_version = row_version + 1"
                                        + " WHERE series_id = ?")) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除知识库失败：" + e.getMessage(), e);
        }
    }

    public void updateCounters(
            long id, int docCount, int readyCount, int failedCount, long chunkCount) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_series SET doc_count = ?, ready_doc_count = ?,"
                                        + " failed_doc_count = ?, chunk_count = ?, updated_at = ?"
                                        + " WHERE series_id = ?")) {
            statement.setInt(1, docCount);
            statement.setInt(2, readyCount);
            statement.setInt(3, failedCount);
            statement.setLong(4, chunkCount);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.setLong(6, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("刷新知识库统计失败：" + e.getMessage(), e);
        }
    }

    private Series map(ResultSet rs) throws SQLException {
        return new Series(
                rs.getLong("series_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("dept_id"),
                rs.getLong("author_id"),
                IndexStrategy.parse(rs.getString("index_strategy"), IndexStrategy.HYBRID),
                SeriesStatus.parse(rs.getString("status"), SeriesStatus.ACTIVE),
                longOrNull(rs, "active_config_version_id"),
                rs.getInt("doc_count"),
                rs.getInt("ready_doc_count"),
                rs.getInt("failed_doc_count"),
                rs.getLong("chunk_count"),
                rs.getLong("row_version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "deleted_at"));
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
