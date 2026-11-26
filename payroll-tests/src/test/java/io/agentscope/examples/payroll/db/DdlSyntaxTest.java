package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.skill.SkillIds;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 共享迁移脚本要在 H2(MySQL 模式) 与真 MySQL 上产出同一套结构。
 *
 * <p>断言只走 JDBC 元数据，两条腿复用；保留字与列宽这类「H2 放行、MySQL 才炸」的问题，
 * 靠建表跑通是发现不了的。
 */
class DdlSyntaxTest {

    /**
     * MySQL 8.0 关键字表里标 (R) 的保留字。H2(MySQL 模式) 只拦其中一部分，而 CI 不连真库：
     * 「H2 建得出来、MySQL 直接报错」的名字只能在这里挡，否则要到生产切换那天才暴露。
     */
    private static final String MYSQL_RESERVED =
            """
            accessible add all alter analyze and as asc asensitive between bigint
            binary blob both by call cascade case change char character check collate
            column condition continue convert create cross cube current_date
            current_time current_timestamp current_user cursor database databases
            day_hour day_microsecond day_minute day_second dec decimal declare
            default delayed delete dense_rank desc describe deterministic distinct
            distinctrow div double drop dual each else elseif empty enclosed escaped
            except exists exit false fetch first_value float float4 float8 for force
            foreign from fulltext function generated get grant grouping groups having
            high_priority hour_microsecond hour_minute hour_second if ignore in
            infile inner inout insensitive insert int int1 int2 int3 int4 int8
            integer intersect interval into io_after_gtids io_before_gtids is iterate
            join json_table key keys kill lag last_value lateral lead leading leave
            left like limit linear lines load localtime localtimestamp lock long
            longblob longtext loop low_priority master_bind match maxvalue mediumblob
            mediumint mediumtext middleint minute_microsecond minute_second mod
            modifies natural not null numeric of on optimize optimizer_costs option
            optionally or order out outer outfile over partition percent_rank
            precision primary procedure purge range rank read_write reads real
            recursive references regexp release rename repeat replace require
            restrict return right rlike row row_number rows schema schemas
            second_microsecond select sensitive separator set show signal smallint
            spatial specific sql sql_big_result sql_calc_found_rows sql_small_result
            sqlexception sqlstate sqlwarning ssl starting stored straight_join system
            table terminated then tinyblob tinyint tinytext to trailing trigger true
            undo union unique unlock unsigned update usage use using utc_date
            utc_time utc_timestamp values varbinary varchar varcharacter varying
            virtual when where while window with write xor year_month zerofill""";

    private static final Set<String> RESERVED =
            Set.of(MYSQL_RESERVED.toLowerCase(Locale.ROOT).split("\\s+"));

    /** V22 建出的索引：表、列序、唯一性一并锁定，改名或漏列都要在这里失败。 */
    private static final Map<String, String> EXPECTED_SKILL_INDEXES =
            Map.of(
                    "uk_skill_version_no", "skill_version:skill_id,version_no:true",
                    "uk_skill_resource_path", "skill_resource:version_id,path:true",
                    "idx_skill_version_hash", "skill_version:content_hash:false",
                    "idx_skill_resource_skill", "skill_resource:skill_id,path:false",
                    "idx_skill_resource_blob", "skill_resource:sha256:false",
                    "idx_skill_def_updated", "skill_definition:updated_at,skill_id:false");

    private final PayrollDatabase database = PayrollDatabase.inMemory("ddl-" + System.nanoTime());

    @Test
    @DisplayName("全部列名与索引名都不是 MySQL 保留字")
    void identifiersAvoidReservedWords() throws Exception {
        try (Connection connection = database.connection()) {
            assertIdentifiersPortable(connection);
        }
    }

    @Test
    @DisplayName("技能四表的索引名、列序与唯一性符合运行期查询")
    void skillIndexesMatchRuntimeQueries() throws Exception {
        try (Connection connection = database.connection()) {
            assertSkillIndexes(connection);
        }
    }

    @Test
    @DisplayName("skill_id 列宽与 SkillIds 的 ID 规则同源，且存得下满长 ID")
    void skillIdColumnsMatchIdRule() throws Exception {
        try (Connection connection = database.connection()) {
            assertSkillIdColumns(connection);
            insertMaxLengthId(connection);
        }
    }

    @Test
    @DisplayName("同一套迁移在真 MySQL 上执行并产出相同结构")
    void sharedMigrationsApplyOnRealMysql() throws Exception {
        String url = System.getenv("PAYROLL_TEST_MYSQL_JDBC");
        String user = System.getenv("PAYROLL_TEST_MYSQL_USER");
        String password = System.getenv("PAYROLL_TEST_MYSQL_PASSWORD");
        // 未配置就跳过：不能让「全绿」被读成 MySQL 已验证。目标库必须是专用空库。
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 PAYROLL_TEST_MYSQL_JDBC");
        try {
            Flyway.configure()
                    .locations(PayrollDatabase.MIGRATION_LOCATION)
                    .dataSource(url, user, password)
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            // 不带原始消息：Flyway 的失败文本会拼上 JDBC URL。
            throw new IllegalStateException("共享迁移在 MySQL 上执行失败，检查 PAYROLL_TEST_MYSQL_*", e);
        }
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertIdentifiersPortable(connection);
            assertSkillIndexes(connection);
            assertSkillIdColumns(connection);
        }
    }

    private static void assertIdentifiersPortable(Connection connection) throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String table : tables(connection)) {
            for (String column : columnNames(connection, table)) {
                if (RESERVED.contains(column.toLowerCase(Locale.ROOT))) {
                    offenders.add(table + "." + column);
                }
            }
            for (String index : indexColumns(connection, table).keySet()) {
                // MySQL 把聚簇主键索引固定命名为 PRIMARY（改不了，也不需要反引号），它撞保留字表但不是可移植性问题；
                // 这条检查管的是我们自己起的索引名。H2 那边叫 PRIMARY_KEY_xxx，所以只有 MySQL 腿会碰到。
                if ("PRIMARY".equalsIgnoreCase(index)) {
                    continue;
                }
                if (RESERVED.contains(index.toLowerCase(Locale.ROOT))) {
                    offenders.add(table + "#" + index);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "列名/索引名撞上 MySQL 保留字：" + offenders);
    }

    private static void assertSkillIndexes(Connection connection) throws Exception {
        Map<String, String> actual = skillIndexSignatures(connection);
        for (Map.Entry<String, String> expected : EXPECTED_SKILL_INDEXES.entrySet()) {
            String name = expected.getKey();
            // 唯一键折进建表体才能随表幂等重放，而 H2 给约束派生的索引加 _INDEX_n 后缀，MySQL 用原名。
            // 所以名字按前缀认，列序与唯一性仍按完整签名比——改名、漏列、颠倒列序都得在这里红。
            List<String> matches =
                    actual.entrySet().stream()
                            .filter(
                                    entry ->
                                            entry.getKey().equals(name)
                                                    || entry.getKey().startsWith(name + "_index_"))
                            .map(Map.Entry::getValue)
                            .toList();
            assertFalse(matches.isEmpty(), "缺少索引 " + name + "，实际有 " + actual.keySet());
            assertTrue(
                    matches.contains(expected.getValue()),
                    name
                            + " 期望 "
                            + expected.getValue()
                            + " 实际 "
                            + matches);
        }
    }

    private static void assertSkillIdColumns(Connection connection) throws Exception {
        List<String> found = new ArrayList<>();
        for (String table : tables(connection)) {
            Integer size = columnSize(connection, table, "skill_id");
            if (size != null) {
                found.add(table.toLowerCase(Locale.ROOT));
                assertEquals(
                        SkillIds.MAX_ID_LENGTH,
                        size,
                        table + ".skill_id 必须与 ID 上限同宽，否则长 ID 只写不读");
            }
        }
        assertEquals(
                List.of(
                        "skill_creation_origin",
                        "skill_data_state",
                        "skill_definition",
                        "skill_resource",
                        "skill_version"),
                found.stream().sorted().toList());
    }

    private static void insertMaxLengthId(Connection connection) throws Exception {
        String id = "s" + "x".repeat(SkillIds.MAX_ID_LENGTH - 1);
        assertTrue(SkillIds.isValid(id), id);
        // MySQL 腿跑在持久库上，同一 schema 可以反复验；H2 那两条每次都是新库，删不删都一样。
        // 不清的话第二次执行撞主键，测出来的会是「这行已经在」而不是「满长 ID 写得进」。
        try (PreparedStatement cleanup =
                connection.prepareStatement("DELETE FROM skill_version WHERE skill_id = ?")) {
            cleanup.setString(1, id);
            cleanup.executeUpdate();
        }
        try (PreparedStatement cleanup =
                connection.prepareStatement("DELETE FROM skill_definition WHERE skill_id = ?")) {
            cleanup.setString(1, id);
            cleanup.executeUpdate();
        }
        try (PreparedStatement definition =
                connection.prepareStatement(
                        "INSERT INTO skill_definition (skill_id, name, content_hash, revision)"
                                + " VALUES (?, ?, ?, 1)")) {
            definition.setString(1, id);
            definition.setString(2, "满长技能");
            definition.setString(3, "0".repeat(64));
            assertEquals(1, definition.executeUpdate());
        }
        try (PreparedStatement version =
                connection.prepareStatement(
                        "INSERT INTO skill_version (skill_id, version_no, body_md, meta_json,"
                                + " content_hash) VALUES (?, 1, ?, '{}', ?)")) {
            version.setString(1, id);
            version.setString(2, "# 正文");
            version.setString(3, "0".repeat(64));
            assertEquals(1, version.executeUpdate());
        }
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT d.skill_id FROM skill_definition d"
                                + " JOIN skill_version v ON v.skill_id = d.skill_id WHERE d.skill_id = ?")) {
            query.setString(1, id);
            try (ResultSet rs = query.executeQuery()) {
                assertTrue(rs.next(), "满长 skill_id 未能写入并关联");
                assertEquals(id, rs.getString(1));
            }
        }
    }

    /** 索引签名：{@code 表:列1,列2:是否唯一}，小写比较，屏蔽两种库的大小写差异。 */
    private static Map<String, String> skillIndexSignatures(Connection connection)
            throws Exception {
        Map<String, String> signatures = new TreeMap<>();
        for (String table : tables(connection)) {
            if (!table.toLowerCase(Locale.ROOT).startsWith("skill")) {
                continue;
            }
            Map<String, List<String>> columns = indexColumns(connection, table);
            Map<String, Boolean> unique = uniqueFlags(connection, table);
            for (Map.Entry<String, List<String>> entry : columns.entrySet()) {
                signatures.put(
                        entry.getKey().toLowerCase(Locale.ROOT),
                        table.toLowerCase(Locale.ROOT)
                                + ":"
                                + String.join(",", entry.getValue())
                                + ":"
                                + unique.getOrDefault(entry.getKey(), false));
            }
        }
        return signatures;
    }

    private static List<String> tables(Connection connection) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs =
                metaData.getTables(
                        connection.getCatalog(), connection.getSchema(), "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static Set<String> columnNames(Connection connection, String table) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ResultSet rs = columns(connection, table)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private static Integer columnSize(Connection connection, String table, String column)
            throws Exception {
        try (ResultSet rs = columns(connection, table)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return rs.getInt("COLUMN_SIZE");
                }
            }
        }
        return null;
    }

    private static ResultSet columns(Connection connection, String table) throws Exception {
        return connection
                .getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), table, "%");
    }

    private static Map<String, List<String>> indexColumns(Connection connection, String table)
            throws Exception {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (ResultSet rs = indexes(connection, table)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index == null || column == null) {
                    continue;
                }
                columns.computeIfAbsent(index, key -> new ArrayList<>())
                        .add(column.toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static Map<String, Boolean> uniqueFlags(Connection connection, String table)
            throws Exception {
        Map<String, Boolean> unique = new LinkedHashMap<>();
        try (ResultSet rs = indexes(connection, table)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                if (index != null) {
                    unique.putIfAbsent(index, !rs.getBoolean("NON_UNIQUE"));
                }
            }
        }
        return unique;
    }

    private static ResultSet indexes(Connection connection, String table) throws Exception {
        return connection
                .getMetaData()
                .getIndexInfo(
                        connection.getCatalog(), connection.getSchema(), table, false, false);
    }
}
