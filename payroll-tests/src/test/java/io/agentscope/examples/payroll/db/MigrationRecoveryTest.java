package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V23 是非事务 DDL：建到一半失败会留下「对象在、迁移记录没提交」的半成品。
 *
 * <p>恢复口径按规格执行：先清掉本迁移已建的全部对象 → {@code flyway repair} → 重跑整份脚本。
 * 不清理直接重跑必须因对象已存在而失败，否则「半成品自愈」就是假象。
 */
class MigrationRecoveryTest {

    private static final String HISTORY = "\"flyway_schema_history\"";

    /** V23 建出的 19 条索引，清理时必须一条不落，否则重跑还会撞。 */
    private static final List<String> V23_INDEXES =
            List.of(
                    "idx_chat_turns_started",
                    "idx_chat_messages_created",
                    "idx_chat_sessions_created",
                    "idx_chat_attachments_created",
                    "idx_chat_tool_traces_created",
                    "idx_chat_feedback_created",
                    "idx_chat_action_proposals_message",
                    "idx_chat_action_proposals_created",
                    "idx_llm_usage_plane_time",
                    "idx_llm_usage_agent_time",
                    "idx_llm_usage_time_id",
                    "idx_agent_team_runs_created",
                    "idx_kb_chunk_series_seq",
                    "idx_kb_document_series_updated",
                    "idx_kb_document_series_dir_updated",
                    "idx_kb_task_log_created",
                    "idx_kb_task_status_created",
                    "idx_audit_result_time",
                    "idx_profile_extractions_updated");

    @Test
    @DisplayName("不清理半成品直接重跑会因对象已存在失败")
    void rerunWithoutCleanupFailsOnExistingObjects(@TempDir Path dir) throws Exception {
        String url = fileUrl(dir.resolve("dirty"));
        PayrollDatabase.open(url, "sa", "");
        simulatePartialV23(url);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> PayrollDatabase.open(url, "sa", ""));

        assertTrue(
                failure.getMessage().contains("表结构迁移失败"),
                "迁移失败必须走统一包装：实际 " + failure.getMessage());
    }

    @Test
    @DisplayName("清掉半成品 + repair 后重跑成功并记录 V23")
    void cleanupRepairThenRerunSucceeds(@TempDir Path dir) throws Exception {
        String url = fileUrl(dir.resolve("recover"));
        PayrollDatabase.open(url, "sa", "");
        simulatePartialV23(url);
        assertThrows(IllegalStateException.class, () -> PayrollDatabase.open(url, "sa", ""));

        dropV23Objects(url);
        Flyway.configure()
                .locations(PayrollDatabase.MIGRATION_LOCATION)
                .dataSource(url + ";MODE=MySQL", "sa", "")
                .load()
                .repair();

        PayrollDatabase.open(url, "sa", "");

        try (Connection connection = DriverManager.getConnection(url + ";MODE=MySQL", "sa", "")) {
            assertEquals(19, v23IndexCount(connection), "重跑后 19 条索引应全部存在");
            assertTrue(tableExists(connection, "job_lease"), "job_lease 应由重跑建出");
            assertEquals(1, columnCount(connection, "chat_sessions", "context_through_message_id"));
            assertEquals(1, historyRows(connection, "23"), "V23 应记录为成功");
        }
    }

    /** 模拟非事务 DDL 中断：部分索引/表/列已落地，但 V23 迁移记录未提交。 */
    private static void simulatePartialV23(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url + ";MODE=MySQL", "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE job_lease");
            statement.execute("DROP INDEX idx_chat_messages_created");
            statement.execute("ALTER TABLE chat_sessions DROP COLUMN context_through_message_id");
            statement.execute("DELETE FROM " + HISTORY + " WHERE \"version\" = '23'");
        }
    }

    /** 按规格清理本迁移已建的全部对象，再交给 repair 抹掉失败记录。 */
    private static void dropV23Objects(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url + ";MODE=MySQL", "sa", "");
                Statement statement = connection.createStatement()) {
            for (String index : V23_INDEXES) {
                statement.execute("DROP INDEX IF EXISTS " + index);
            }
            statement.execute("DROP TABLE IF EXISTS job_lease");
            statement.execute("ALTER TABLE chat_sessions DROP COLUMN IF EXISTS context_through_message_id");
        }
    }

    private static String fileUrl(Path file) {
        return "jdbc:h2:file:" + file.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private static int v23IndexCount(Connection connection) throws SQLException {
        StringBuilder sql =
                new StringBuilder("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES")
                        .append(" WHERE TABLE_SCHEMA = 'PUBLIC' AND INDEX_NAME IN (");
        for (int i = 0; i < V23_INDEXES.size(); i++) {
            sql.append(i == 0 ? "" : ",").append("'").append(V23_INDEXES.get(i).toUpperCase()).append("'");
        }
        sql.append(")");
        return scalar(connection, sql.toString());
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        return scalar(
                        connection,
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '"
                                + table.toUpperCase()
                                + "'")
                == 1;
    }

    private static int columnCount(Connection connection, String table, String column)
            throws SQLException {
        return scalar(
                connection,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME = '"
                        + table.toUpperCase()
                        + "' AND COLUMN_NAME = '"
                        + column.toUpperCase()
                        + "'");
    }

    private static int historyRows(Connection connection, String version) throws SQLException {
        return scalar(
                connection,
                "SELECT COUNT(*) FROM " + HISTORY + " WHERE \"version\" = '" + version + "'");
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
