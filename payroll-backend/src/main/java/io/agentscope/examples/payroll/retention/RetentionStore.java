package io.agentscope.examples.payroll.retention;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留任务的批删查询。全部走「先取主键、再按主键删除」两步法：
 * MySQL 8 不支持 {@code DELETE ... WHERE pk IN (SELECT ... LIMIT ?)}，两步法跨 H2/MySQL 通用。
 *
 * <p>表名与主键列只来自本类常量，调用方不能拼接 SQL。
 */
public class RetentionStore {

    public static final String PROPOSAL_TERMINAL_STATUSES =
            "('CONFIRMED','REJECTED','EXPIRED','FAILED')";
    public static final List<String> TASK_TERMINAL_STATUSES =
            List.of("SUCCEEDED", "FAILED", "CANCELLED");

    public record TableStat(
            long rows, LocalDateTime earliest, LocalDateTime latest, List<String> samples) {}

    public record AttachmentRow(long id, String storageKey) {}

    public record AttachmentStat(long rows, long files, long bytes) {}

    private final PayrollDatabase database;

    public RetentionStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Long> selectExpiredIds(
            String table, String pk, String timeColumn, LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT "
                        + pk
                        + " FROM "
                        + table
                        + " WHERE "
                        + timeColumn
                        + " < ? ORDER BY "
                        + timeColumn
                        + ", "
                        + pk
                        + " LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
        });
    }

    public int deleteByIds(String table, String pk, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql =
                "DELETE FROM "
                        + table
                        + " WHERE "
                        + pk
                        + " IN ("
                        + placeholders(ids.size())
                        + ")";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, ids);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理 " + table + " 失败：" + e.getMessage(), e);
        }
    }

    public List<String> selectExpiredProposalIds(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT proposal_id FROM chat_action_proposals WHERE created_at < ? AND status IN "
                        + PROPOSAL_TERMINAL_STATUSES
                        + " ORDER BY created_at, proposal_id LIMIT ?";
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("查询过期提案失败：" + e.getMessage(), e);
        }
    }

    public int deleteProposals(List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql =
                "DELETE FROM chat_action_proposals WHERE proposal_id IN ("
                        + placeholders(ids.size())
                        + ")";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setString(i + 1, ids.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理过期提案失败：" + e.getMessage(), e);
        }
    }

    /** 过期 PENDING 先批量置 EXPIRED，下一轮再按终态删除，避免待办永久堆积。 */
    public int expirePendingProposals(LocalDateTime cutoff) {
        String sql =
                "UPDATE chat_action_proposals SET status='EXPIRED', expires_at=CURRENT_TIMESTAMP"
                        + " WHERE status='PENDING' AND created_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("过期提案置 EXPIRED 失败：" + e.getMessage(), e);
        }
    }

    public List<String> selectOrphanProposalIds(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT p.proposal_id FROM chat_action_proposals p"
                        + " WHERE p.created_at < ? AND p.message_id > 0 AND p.status IN "
                        + PROPOSAL_TERMINAL_STATUSES
                        + " AND NOT EXISTS (SELECT 1 FROM chat_messages m"
                        + " WHERE m.message_id = p.message_id)"
                        + " ORDER BY p.created_at, p.proposal_id LIMIT ?";
        return queryStrings(sql, cutoff, limit);
    }

    public List<AttachmentRow> selectExpiredAttachments(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT attachment_id, storage_key FROM chat_attachments"
                        + " WHERE created_at < ? ORDER BY created_at, attachment_id LIMIT ?";
        return queryAttachments(sql, cutoff, limit);
    }

    public List<AttachmentRow> selectOrphanAttachments(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT a.attachment_id, a.storage_key FROM chat_attachments a"
                        + " WHERE a.created_at < ? AND a.message_id IS NOT NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM chat_messages m"
                        + " WHERE m.message_id = a.message_id)"
                        + " ORDER BY a.created_at, a.attachment_id LIMIT ?";
        return queryAttachments(sql, cutoff, limit);
    }

    public int deleteAttachments(List<Long> ids) {
        return deleteByIds("chat_attachments", "attachment_id", ids);
    }

    public List<Long> selectExpiredEmptySessions(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT s.session_id FROM chat_sessions s WHERE s.created_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM chat_messages m"
                        + " WHERE m.session_id = s.session_id)"
                        + " ORDER BY s.created_at, s.session_id LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
        });
    }

    public int deleteSessions(List<Long> ids) {
        return deleteByIds("chat_sessions", "session_id", ids);
    }

    public List<Long> selectExpiredTasks(String status, LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT task_id FROM kb_ingest_task WHERE task_status = ? AND created_at < ?"
                        + " ORDER BY created_at, task_id LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setString(1, status);
            statement.setTimestamp(2, Timestamp.valueOf(cutoff));
            statement.setInt(3, limit);
        });
    }

    public List<Long> selectOrphanTraces(LocalDateTime cutoff, int limit) {
        return selectOrphanLongIds(
                "chat_tool_traces", "trace_id", "message_id", cutoff, limit);
    }

    public List<Long> selectOrphanFeedback(LocalDateTime cutoff, int limit) {
        return selectOrphanLongIds("chat_feedback", "feedback_id", "message_id", cutoff, limit);
    }

    public List<Long> selectOrphanTaskLogs(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT l.log_id FROM kb_task_log l WHERE l.created_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM kb_ingest_task t"
                        + " WHERE t.task_id = l.task_id)"
                        + " ORDER BY l.created_at, l.log_id LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
        });
    }

    public List<Long> selectArchivedTeamRuns(LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT run_id FROM agent_team_runs WHERE deleted_at IS NOT NULL"
                        + " AND created_at < ? ORDER BY created_at, run_id LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
        });
    }

    public int deleteTeamRuns(List<Long> ids) {
        return deleteByIds("agent_team_runs", "run_id", ids);
    }

    public TableStat tableStat(
            String table, String pk, String timeColumn, LocalDateTime cutoff) {
        String statSql =
                "SELECT COUNT(*), MIN("
                        + timeColumn
                        + "), MAX("
                        + timeColumn
                        + ") FROM "
                        + table
                        + " WHERE "
                        + timeColumn
                        + " < ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(statSql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                long count = rows.getLong(1);
                Timestamp earliest = rows.getTimestamp(2);
                Timestamp latest = rows.getTimestamp(3);
                List<String> samples = new ArrayList<>();
                if (count > 0) {
                    String sampleSql =
                            "SELECT "
                                    + pk
                                    + " FROM "
                                    + table
                                    + " WHERE "
                                    + timeColumn
                                    + " < ? ORDER BY "
                                    + timeColumn
                                    + ", "
                                    + pk
                                    + " LIMIT 5";
                    try (PreparedStatement sample = connection.prepareStatement(sampleSql)) {
                        sample.setTimestamp(1, Timestamp.valueOf(cutoff));
                        try (ResultSet sampleRows = sample.executeQuery()) {
                            while (sampleRows.next()) {
                                samples.add(sampleRows.getString(1));
                            }
                        }
                    }
                }
                return new TableStat(
                        count,
                        earliest == null ? null : earliest.toLocalDateTime(),
                        latest == null ? null : latest.toLocalDateTime(),
                        samples);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 " + table + " 失败：" + e.getMessage(), e);
        }
    }

    public TableStat proposalStat(LocalDateTime cutoff) {
        String sql =
                "SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM chat_action_proposals"
                        + " WHERE created_at < ? AND status IN "
                        + PROPOSAL_TERMINAL_STATUSES;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                Timestamp earliest = rows.getTimestamp(2);
                Timestamp latest = rows.getTimestamp(3);
                return new TableStat(
                        rows.getLong(1),
                        earliest == null ? null : earliest.toLocalDateTime(),
                        latest == null ? null : latest.toLocalDateTime(),
                        List.of());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计过期提案失败：" + e.getMessage(), e);
        }
    }

    public AttachmentStat attachmentStat(LocalDateTime cutoff) {
        String sql =
                "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM chat_attachments"
                        + " WHERE created_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new AttachmentStat(rows.getLong(1), rows.getLong(1), rows.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计过期附件失败：" + e.getMessage(), e);
        }
    }

    public boolean attachmentKeyExists(String storageKey) {
        String sql = "SELECT COUNT(*) FROM chat_attachments WHERE storage_key = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageKey);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查附件路径失败：" + e.getMessage(), e);
        }
    }

    private List<Long> selectOrphanLongIds(
            String table, String pk, String messageColumn, LocalDateTime cutoff, int limit) {
        String sql =
                "SELECT t."
                        + pk
                        + " FROM "
                        + table
                        + " t WHERE t."
                        + messageColumn
                        + " > 0 AND t.created_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM chat_messages m"
                        + " WHERE m.message_id = t."
                        + messageColumn
                        + ")"
                        + " ORDER BY t.created_at, t."
                        + pk
                        + " LIMIT ?";
        return queryLongs(sql, statement -> {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
        });
    }

    private List<String> queryStrings(String sql, LocalDateTime cutoff, int limit) {
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("查询孤儿行失败：" + e.getMessage(), e);
        }
    }

    private List<AttachmentRow> queryAttachments(String sql, LocalDateTime cutoff, int limit) {
        List<AttachmentRow> rows = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AttachmentRow(result.getLong(1), result.getString(2)));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("查询过期附件失败：" + e.getMessage(), e);
        }
    }

    private List<Long> queryLongs(String sql, Binder binder) {
        List<Long> ids = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("查询过期行失败：" + e.getMessage(), e);
        }
    }

    private static void bindIds(PreparedStatement statement, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            statement.setLong(i + 1, ids.get(i));
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
