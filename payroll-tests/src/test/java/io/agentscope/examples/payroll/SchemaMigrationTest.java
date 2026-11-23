package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 共享 Flyway 迁移必须在 H2(MySQL 模式) 与 MySQL 上产出同一套表结构。 */
class SchemaMigrationTest {

    private static final String HISTORY = "\"flyway_schema_history\"";

    @Test
    @DisplayName("H2 以 MySQL 模式执行共享迁移，建出全部表与增量列")
    void h2AppliesSharedMigrations() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("schema-" + System.nanoTime());
        try (Connection connection = database.connection()) {
            assertEquals(44, tableCount(connection));
            assertColumn(connection, "chat_messages", "routing_json");
            assertColumn(connection, "chat_messages", "citations_json");
            assertColumn(connection, "chat_messages", "turn_id");
            assertColumn(connection, "chat_turns", "deadline_at");
            assertColumn(connection, "chat_turns", "heartbeat_at");
            assertColumn(connection, "chat_attachments", "extraction_meta");
            assertColumn(connection, "app_users", "must_change_password");
            assertColumn(connection, "audit_log", "write_result");
            assertColumn(connection, "dev_tasks", "finished_at");
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES"
                    + " WHERE INDEX_NAME = 'UK_CHAT_MESSAGES_TURN'"));
            assertEquals("MySQL", setting(connection, "MODE"));
        }
    }

    @Test
    @DisplayName("重复打开同一库复用迁移历史，不重复灌入演示数据")
    void reopeningReusesMigrationHistory() throws Exception {
        String url = "jdbc:h2:mem:payroll-reopen-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        PayrollDatabase first = PayrollDatabase.open(url, "sa", "");
        int seeded;
        try (Connection connection = first.connection()) {
            seeded = scalar(connection, "SELECT COUNT(*) FROM employees");
        }
        first.close();

        PayrollDatabase second = PayrollDatabase.open(url, "sa", "");
        try (Connection connection = second.connection()) {
            assertTrue(seeded > 0, "演示员工应在首次初始化时写入");
            assertEquals(seeded, scalar(connection, "SELECT COUNT(*) FROM employees"));
            assertEquals(20, scalar(connection, "SELECT COUNT(*) FROM " + HISTORY
                    + " WHERE \"version\" IS NOT NULL"));
        }
    }

    @Test
    @DisplayName("统一迁移前创建的 H2 库按基线放行，既有数据保留")
    void legacyDatabaseIsBaselinedWithoutDataLoss(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("legacy").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_users (user_id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("INSERT INTO app_users (user_id) VALUES (7)");
        }

        PayrollDatabase database = PayrollDatabase.open(url, "sa", "");
        try (Connection connection = database.connection()) {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM app_users"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM " + HISTORY
                    + " WHERE \"type\" = 'BASELINE' AND \"version\" = '21'"));
        }
    }

    private static int tableCount(Connection connection) throws SQLException {
        return scalar(
                connection,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> 'flyway_schema_history'");
    }

    private static void assertColumn(Connection connection, String table, String column)
            throws SQLException {
        assertEquals(
                1,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                                + " WHERE TABLE_NAME = '"
                                + table.toUpperCase()
                                + "' AND COLUMN_NAME = '"
                                + column.toUpperCase()
                                + "'"),
                table + "." + column);
    }

    private static String setting(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                                        + " WHERE SETTING_NAME = '"
                                        + name
                                        + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
