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
            assertEquals(49, tableCount(connection));
            assertColumn(connection, "chat_messages", "routing_json");
            assertColumn(connection, "chat_messages", "citations_json");
            assertColumn(connection, "chat_messages", "turn_id");
            assertColumn(connection, "chat_turns", "deadline_at");
            assertColumn(connection, "chat_turns", "heartbeat_at");
            assertColumn(connection, "chat_attachments", "extraction_meta");
            assertColumn(connection, "app_users", "must_change_password");
            assertColumn(connection, "audit_log", "write_result");
            assertColumn(connection, "dev_tasks", "finished_at");
            assertColumn(connection, "agent_definition", "payload_json");
            assertColumn(connection, "skill_definition", "resource_hash");
            assertColumn(connection, "skill_definition", "sort_order");
            assertColumn(connection, "skill_version", "body_md");
            assertColumn(connection, "skill_resource", "is_binary");
            assertColumn(connection, "skill_blob", "storage_location");
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
            assertEquals(22, scalar(connection, "SELECT COUNT(*) FROM " + HISTORY
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

    @Test
    @DisplayName("迁移前的历史 H2 库补建到基线为止，V22 由 Flyway 真正执行")
    void v21LegacyDatabaseAppliesV22AfterBaseline(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("at-v21").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        // 基线脚本的幂等补建会建出 V1–V21 的全部对象；若重放越界到 V22，
        // Flyway 随后真正执行 V22 会撞上「对象已存在」，启动直接失败。
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_users (user_id BIGINT AUTO_INCREMENT PRIMARY KEY)");
        }

        PayrollDatabase database = PayrollDatabase.open(url, "sa", "");
        try (Connection connection = database.connection()) {
            assertTable(connection, "skill_definition");
            assertTable(connection, "skill_version");
            assertTable(connection, "skill_resource");
            assertTable(connection, "skill_blob");
            assertEquals(49, tableCount(connection));
            assertHistoryVersion(connection, "21");
            assertHistoryVersion(connection, "22");
        }
    }

    @Test
    @DisplayName("半迁移的历史 H2 库重放增量脚本不冲突")
    void partiallyMigratedLegacyDatabaseReplaysIdempotently(@TempDir Path dir) throws Exception {
        String url =
                "jdbc:h2:file:" + dir.resolve("half-migrated").toAbsolutePath()
                        + ";DB_CLOSE_ON_EXIT=FALSE";
        // 上一进程补建到一半被杀：V22 的表已在，但没有迁移记录，下次启动必须原样重放。
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_users (user_id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("CREATE TABLE agent_definition (id VARCHAR(128) PRIMARY KEY)");
            statement.execute(
                    "CREATE TABLE skill_definition (skill_id VARCHAR(64) PRIMARY KEY,"
                            + " name VARCHAR(128) NOT NULL, description VARCHAR(512)"
                            + " NOT NULL DEFAULT '', tags VARCHAR(512) NOT NULL DEFAULT '',"
                            + " skill_version VARCHAR(32) NOT NULL DEFAULT '', builtin BOOLEAN"
                            + " NOT NULL DEFAULT FALSE, sort_order INT NOT NULL DEFAULT 0,"
                            + " execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                            + " content_hash CHAR(64) NOT NULL, content_length INT NOT NULL"
                            + " DEFAULT 0, resource_count INT NOT NULL DEFAULT 0,"
                            + " resource_bytes BIGINT NOT NULL DEFAULT 0, resource_hash"
                            + " CHAR(64) NOT NULL DEFAULT '', current_version_id BIGINT,"
                            + " revision BIGINT NOT NULL, created_by VARCHAR(128),"
                            + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + " updated_by VARCHAR(128), updated_at TIMESTAMP NOT NULL"
                            + " DEFAULT CURRENT_TIMESTAMP)");
            statement.execute(
                    "CREATE TABLE skill_version (version_id BIGINT AUTO_INCREMENT PRIMARY"
                            + " KEY, skill_id VARCHAR(64) NOT NULL, version_no INT NOT NULL,"
                            + " body_md LONGTEXT NOT NULL, meta_json TEXT NOT NULL, content_hash"
                            + " CHAR(64) NOT NULL, content_length INT NOT NULL DEFAULT 0,"
                            + " change_note VARCHAR(200) NOT NULL DEFAULT '',"
                            + " created_by VARCHAR(128), created_at TIMESTAMP NOT NULL"
                            + " DEFAULT CURRENT_TIMESTAMP)");
            statement.execute(
                    "CREATE UNIQUE INDEX uk_skill_version_no ON skill_version"
                            + " (skill_id, version_no)");
        }

        PayrollDatabase database = PayrollDatabase.open(url, "sa", "");
        try (Connection connection = database.connection()) {
            assertTable(connection, "skill_resource");
            assertTable(connection, "skill_blob");
            assertEquals(49, tableCount(connection));
            assertHistoryVersion(connection, "22");
        }
    }

    /** 打基线的历史库只留基线行与本批版本行，不会补齐 V1–V20。 */
    private static void assertHistoryVersion(Connection connection, String version)
            throws SQLException {
        assertEquals(
                1,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM "
                                + HISTORY
                                + " WHERE \"version\" = '"
                                + version
                                + "'"),
                "迁移历史缺少版本 " + version);
    }

    private static void assertTable(Connection connection, String table) throws SQLException {
        assertEquals(
                1,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '"
                                + table.toUpperCase()
                                + "'"),
                table);
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
