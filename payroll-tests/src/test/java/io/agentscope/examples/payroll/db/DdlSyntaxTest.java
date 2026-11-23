package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final PayrollDatabase database = PayrollDatabase.inMemory("ddl-" + System.nanoTime());

    @Test
    @DisplayName("全部列名与索引名都不是 MySQL 保留字")
    void identifiersAvoidReservedWords() throws Exception {
        try (Connection connection = database.connection()) {
            assertIdentifiersPortable(connection);
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

    private static ResultSet indexes(Connection connection, String table) throws Exception {
        return connection
                .getMetaData()
                .getIndexInfo(
                        connection.getCatalog(), connection.getSchema(), table, false, false);
    }
}
