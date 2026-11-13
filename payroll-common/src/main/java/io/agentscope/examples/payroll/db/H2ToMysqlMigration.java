package io.agentscope.examples.payroll.db;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次性迁移：把现有 H2 文件库的全部业务数据复制到 MySQL。
 *
 * <p>目标库结构由 Flyway 创建，数据按表使用 REPLACE INTO 保留主键；迁移后可重复执行。
 * 执行前必须停止正在运行的 Web 实例，避免 H2 单写者锁。
 */
public final class H2ToMysqlMigration {

    private static final Logger log = LoggerFactory.getLogger(H2ToMysqlMigration.class);
    private static final int BATCH_SIZE = 500;

    private H2ToMysqlMigration() {
    }

    public static void run(
            String h2Url,
            String h2User,
            String h2Password,
            String mysqlUrl,
            String mysqlUser,
            String mysqlPassword,
            boolean force) {
        if (!mysqlUrl.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("目标库必须是 MySQL：" + mysqlUrl);
        }
        try {
            Flyway.configure()
                    .dataSource(mysqlUrl, mysqlUser, mysqlPassword)
                    .locations("classpath:db/migration/mysql")
                    .table("flyway_schema_history")
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "目标库迁移失败：目标库必须为空库或已由 Flyway 管理；请勿手工建表。原始错误："
                            + e.getMessage(),
                    e);
        }
        log.info("MySQL 表结构已就绪：{}", mysqlUrl);

        try (Connection source = DriverManager.getConnection(h2Url, h2User, h2Password);
                Connection target =
                        DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword)) {
            List<String> tables = sourceTables(source);
            Map<String, int[]> summary = new LinkedHashMap<>();
            for (String table : tables) {
                int[] counts = copyTable(source, target, table, force);
                summary.put(table, counts);
            }
            printSummary(summary);
        } catch (SQLException e) {
            throw new IllegalStateException("迁移失败：" + e.getMessage(), e);
        }
    }

    private static List<String> sourceTables(Connection source) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = source.getMetaData();
        try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name.startsWith("INFORMATION_SCHEMA")) {
                    continue;
                }
                tables.add(name);
            }
        }
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        return tables;
    }

    private static int[] copyTable(
            Connection source, Connection target, String h2Table, boolean force)
            throws SQLException {
        String table = h2Table.toLowerCase(Locale.ROOT);
        List<String> columns = columns(source, "PUBLIC", h2Table);
        Set<String> targetColumns = new LinkedHashSet<>(columns(target, null, table));
        for (String column : columns) {
            if (!targetColumns.contains(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "目标表 " + table + " 缺少列 " + column + "，请检查 Flyway 版本");
            }
        }
        int targetRows = count(target, table);
        if (targetRows > 0) {
            if (!force) {
                throw new IllegalStateException(
                        "目标表 " + table + " 已有 " + targetRows + " 行；确认覆盖请加 --force");
            }
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate("DELETE FROM `" + table + "`");
            }
        }

        String columnList =
                String.join(
                        ", ",
                        columns.stream().map(c -> "`" + c.toLowerCase(Locale.ROOT) + "`").toList());
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        String insertSql =
                "REPLACE INTO `" + table + "` (" + columnList + ") VALUES (" + placeholders + ")";
        String selectSql =
                "SELECT "
                        + String.join(", ", columns.stream().map(c -> "\"" + c + "\"").toList())
                        + " FROM "
                        + h2Table;

        int copied = 0;
        target.setAutoCommit(false);
        try (PreparedStatement select = source.prepareStatement(selectSql);
                ResultSet rs = select.executeQuery();
                PreparedStatement insert = target.prepareStatement(insertSql)) {
            while (rs.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof Clob clob) {
                        value = clob.getSubString(1, (int) clob.length());
                    }
                    insert.setObject(i, value);
                }
                insert.addBatch();
                if (++copied % BATCH_SIZE == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        target.commit();
        target.setAutoCommit(true);

        int verified = count(target, table);
        if (verified != copied) {
            throw new IllegalStateException(
                    "迁移校验失败：" + table + " 源 " + copied + " 行，目标 " + verified + " 行");
        }
        return new int[] {copied, verified};
    }

    private static List<String> columns(Connection connection, String schema, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs =
                connection.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void printSummary(Map<String, int[]> summary) {
        StringBuilder builder = new StringBuilder("H2 → MySQL 迁移完成：\n");
        long total = 0;
        for (Map.Entry<String, int[]> entry : summary.entrySet()) {
            int rows = entry.getValue()[0];
            total += rows;
            builder.append(String.format("  %-28s %d 行%n", entry.getKey(), rows));
        }
        builder.append("  合计 ").append(total).append(" 行");
        System.out.println(builder);
    }
}
