package io.agentscope.examples.payroll.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.support.Permissions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * 薪酬管理台数据库：默认 H2 文件库，部门部署可切换 MySQL。
 *
 * <p>两种库共用同一套 Flyway 迁移（classpath:db/migration/common）：H2 以 MySQL 兼容模式执行，
 * MySQL 原样执行，表结构只维护一份。HikariCP 只在 MySQL 下提供连接池，方言差异集中在 upsert
 * 与种子 SQL。
 *
 * <p>默认文件为 {@code ./data/payroll.mv.db}。首次启动自动建表，并把 classpath 中的演示数据
 * 迁移进库；之后所有修改都以数据库为准。
 */
public final class PayrollDatabase {

    static final String MIGRATION_LOCATION = "classpath:db/migration/common";
    /** 统一迁移前创建的 H2 库按 V21 打基线，避免对既有数据重放历史迁移。 */
    private static final String H2_BASELINE_VERSION = "21";

    public static final String DEFAULT_URL =
            "jdbc:h2:file:./data/payroll;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL";
    private static final String DEFAULT_USER = "sa";
    private static final String DEFAULT_PASSWORD = "";

    private static final Map<String, PayrollPolicy> DEMO_CITY_POLICIES = demoCityPolicies();

    private final String url;
    private final String user;
    private final String password;
    private final boolean mysql;
    private final HikariDataSource pool;

    private PayrollDatabase(String url, String user, String password, int poolSize) {
        this.url = withMysqlMode(url);
        this.user = user;
        this.password = password;
        this.mysql = this.url.startsWith("jdbc:mysql:");
        this.pool = mysql ? createPool(this.url, user, password, poolSize) : null;
    }

    /** 共享迁移含 MySQL 语法，H2 必须以 MySQL 兼容模式打开。 */
    private static String withMysqlMode(String url) {
        if (!url.startsWith("jdbc:h2:") || url.toUpperCase(Locale.ROOT).contains("MODE=")) {
            return url;
        }
        return url + (url.endsWith(";") ? "" : ";") + "MODE=MySQL";
    }

    /** MySQL 使用连接池；H2 保持单写者直连，避免本地/测试创建大量池与后台线程。 */
    private static HikariDataSource createPool(
            String url, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("payroll-mysql");
        config.setMaximumPoolSize(poolSize > 0 ? poolSize : envInt("PAYROLL_DB_POOL_SIZE", 16));
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setAutoCommit(true);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        return new HikariDataSource(config);
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static PayrollDatabase openDefault() {
        String url = env("PAYROLL_DB_URL", DEFAULT_URL);
        String user = env("PAYROLL_DB_USER", DEFAULT_USER);
        String password = env("PAYROLL_DB_PASSWORD", DEFAULT_PASSWORD);
        return open(url, user, password);
    }

    public static PayrollDatabase open(String url, String user, String password) {
        return open(url, user, password, -1);
    }

    /** 按显式连接参数初始化数据库，供 Spring 配置或嵌入式场景使用。 */
    public static PayrollDatabase open(String url, String user, String password, int poolSize) {
        PayrollDatabase database =
                new PayrollDatabase(
                        url == null || url.isBlank() ? DEFAULT_URL : url.trim(),
                        user == null || user.isBlank() ? DEFAULT_USER : user,
                        password == null ? DEFAULT_PASSWORD : password,
                        poolSize);
        database.initialize();
        return database;
    }

    /** 测试使用：内存库，生命周期跟随 JVM。 */
    public static PayrollDatabase inMemory(String suffix) {
        PayrollDatabase database =
                new PayrollDatabase(
                        "jdbc:h2:mem:payroll-" + suffix + ";DB_CLOSE_DELAY=-1",
                        DEFAULT_USER,
                        DEFAULT_PASSWORD,
                        -1);
        database.initialize();
        return database;
    }

    public Connection connection() throws SQLException {
        return pool != null ? pool.getConnection() : DriverManager.getConnection(url, user, password);
    }

    public String url() {
        return url;
    }

    public boolean isMysql() {
        return mysql;
    }

    /** 按方言选择 SQL；MySQL 分支只在 mysql profile 下使用。 */
    public String dialect(String h2Sql, String mysqlSql) {
        return mysql ? mysqlSql : h2Sql;
    }

    /** JVM 退出或 Spring 容器关闭时释放连接池。 */
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }

    private void initialize() {
        if (!mysql) {
            ensureDirectory();
        }
        migrate();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            seedCityPolicies(connection);
            seedReferenceData(connection);
            seedIdentityData(connection);
            seedApprovalFlows(connection);
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "初始化 " + (mysql ? "MySQL" : "H2") + " 数据库失败：" + e.getMessage(), e);
        }
    }

    /**
     * 表结构统一由 Flyway 版本化管理，H2 与 MySQL 执行同一套脚本。
     *
     * <p>H2 本地库在统一迁移前由内联 DDL 创建，没有迁移历史：先用同一套脚本幂等补齐结构，
     * 再按 V21 打基线，既有数据不动，后续版本正常增量迁移。
     */
    private void migrate() {
        var config =
                Flyway.configure()
                        .locations(MIGRATION_LOCATION)
                        .table("flyway_schema_history");
        if (pool != null) {
            config = config.dataSource(pool);
        } else {
            upgradeLegacyH2();
            config =
                    config.dataSource(url, user, password)
                            .baselineOnMigrate(true)
                            .baselineVersion(H2_BASELINE_VERSION);
        }
        try {
            config.load().migrate();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    (mysql ? "MySQL" : "H2")
                            + " 表结构迁移失败：请确认目标库为空库或已由 Flyway 管理，"
                            + "且未手工修改过表结构。原始错误："
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * 统一迁移前的 H2 库没有 flyway_schema_history，直接打基线会漏建后续版本的表。
     *
     * <p>按版本顺序重放同一套脚本，把 CREATE TABLE / CREATE INDEX / ADD COLUMN 改写成
     * IF NOT EXISTS 形式，幂等补齐缺失结构；之后由 Flyway 记录 V21 基线。
     */
    private void upgradeLegacyH2() {
        if (hasSchemaHistory() || tableCount() == 0) {
            return;
        }
        Flyway flyway =
                Flyway.configure()
                        .locations(MIGRATION_LOCATION)
                        .dataSource(url, user, password)
                        .load();
        for (var info : flyway.info().all()) {
            if (info.getVersion() == null
                    || info.getScript() == null
                    || !appliedBaseline(info.getVersion().toString(), H2_BASELINE_VERSION)) {
                continue;
            }
            for (String statement : splitStatements(readMigration(info.getScript()))) {
                executeIfNotExists(statement);
            }
        }
    }

    /**
     * 补建只重放到基线版本为止：基线之后的脚本要留给 Flyway 真正执行，否则历史库会被剧透成
     * 「对象已存在、但没有迁移记录」，下一次真实迁移撞上 {@code CREATE TABLE} 直接失败。
     */
    static boolean appliedBaseline(String version, String baseline) {
        String[] left = version.split("\\.");
        String[] right = baseline.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? Integer.parseInt(left[i]) : 0;
            int b = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (a != b) {
                return a < b;
            }
        }
        return true;
    }

    private boolean hasSchemaHistory() {
        return scalar(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'flyway_schema_history'") > 0;
    }

    private int tableCount() {
        return scalar(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
    }

    private int scalar(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库元数据失败：" + e.getMessage(), e);
        }
    }

    private static String readMigration(String script) {
        String path = MIGRATION_LOCATION.replace("classpath:", "") + "/" + script;
        try (InputStream in = PayrollDatabase.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到迁移脚本：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + path, e);
        }
    }

    /** 迁移脚本只含单引号字符串与 -- 行注释，按分号切分即可。 */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!quoted && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                current.append('\n');
                continue;
            }
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == ';' && !quoted) {
                addStatement(statements, current);
            } else {
                current.append(c);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private void executeIfNotExists(String statement) {
        String idempotent =
                statement
                        .replaceFirst(
                                "(?is)^CREATE\\s+TABLE\\s+(?!IF\\s+NOT\\s+EXISTS)",
                                "CREATE TABLE IF NOT EXISTS ")
                        .replaceFirst(
                                "(?is)^CREATE\\s+UNIQUE\\s+INDEX\\s+(?!IF\\s+NOT\\s+EXISTS)",
                                "CREATE UNIQUE INDEX IF NOT EXISTS ")
                        .replaceFirst(
                                "(?is)^CREATE\\s+INDEX\\s+(?!IF\\s+NOT\\s+EXISTS)",
                                "CREATE INDEX IF NOT EXISTS ")
                        .replaceFirst(
                                "(?is)^(ALTER\\s+TABLE\\s+\\S+\\s+ADD\\s+COLUMN\\s+)(?!IF\\s+NOT\\s+EXISTS)",
                                "$1IF NOT EXISTS ");
        try (Connection connection = connection();
                Statement jdbc = connection.createStatement()) {
            jdbc.execute(idempotent);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "补建历史 H2 表结构失败：" + e.getMessage() + "\nSQL: " + idempotent, e);
        }
    }

    private void ensureDirectory() {
        String prefix = "jdbc:h2:file:";
        if (!url.startsWith(prefix)) {
            return;
        }
        String path = url.substring(prefix.length()).split(";", 2)[0];
        Path parent = Path.of(path).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据库目录：" + parent, e);
        }
    }

    private void seedCityPolicies(Connection connection) throws SQLException {
        String sql =
                dialect(
                        """
                        INSERT INTO city_policies (city, policy_json, updated_at)
                        SELECT ?, ?, ? WHERE NOT EXISTS
                            (SELECT 1 FROM city_policies WHERE city = ?)
                        """,
                        """
                        INSERT INTO city_policies (city, policy_json, updated_at)
                        SELECT ?, ?, ? FROM DUAL WHERE NOT EXISTS
                            (SELECT 1 FROM city_policies WHERE city = ?)
                        """);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, PayrollPolicy> entry : DEMO_CITY_POLICIES.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, JsonSupport.toJson(entry.getValue()));
                statement.setObject(3, LocalDateTime.now());
                statement.setString(4, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 仅在员工表为空时导入演示数据，避免覆盖管理台已经维护的内容。 */
    private void seedReferenceData(Connection connection) throws SQLException {
        if (count(connection, "employees") > 0) {
            seedReviewPlaceholders(connection);
            return;
        }
        JsonPayrollRepository demo = JsonPayrollRepository.demo();
        String employeeSql =
                """
                INSERT INTO employees (employee_id, name, department, job_title, base_salary,
                    position_allowance, housing_fund_rate, social_insurance_base,
                    special_additional_deduction, hire_date, bank_account_masked, email, city,
                    active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(employeeSql)) {
            for (Employee employee : demo.employees()) {
                statement.setString(1, employee.employeeId());
                statement.setString(2, employee.name());
                statement.setString(3, employee.department());
                statement.setString(4, employee.position());
                statement.setBigDecimal(5, employee.baseSalary());
                statement.setBigDecimal(6, employee.positionAllowance());
                statement.setBigDecimal(7, employee.housingFundRate());
                statement.setBigDecimal(8, employee.socialInsuranceBase());
                statement.setBigDecimal(9, employee.specialAdditionalDeduction());
                statement.setObject(10, employee.hireDate());
                statement.setString(11, employee.bankAccountMasked());
                statement.setString(12, employee.email());
                statement.setString(13, employee.city());
                statement.setObject(14, LocalDateTime.now());
                statement.addBatch();
            }
            statement.executeBatch();
        }

        YearMonth through = demo.dataThroughPeriod();
        String attendanceSql =
                """
                INSERT INTO attendance (employee_id, period, personal_leave_days, sick_leave_days,
                    absent_days, weekday_overtime_hours, rest_day_overtime_hours,
                    holiday_overtime_hours)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(attendanceSql)) {
            for (Employee employee : demo.employees()) {
                for (YearMonth month = YearMonth.from(employee.hireDate());
                        !month.isAfter(through);
                        month = month.plusMonths(1)) {
                    AttendanceSummary attendance =
                            demo.findAttendance(employee.employeeId(), month).orElse(null);
                    if (attendance == null) {
                        continue;
                    }
                    statement.setString(1, attendance.employeeId());
                    statement.setString(2, attendance.period());
                    statement.setBigDecimal(3, attendance.personalLeaveDays());
                    statement.setBigDecimal(4, attendance.sickLeaveDays());
                    statement.setBigDecimal(5, attendance.absentDays());
                    statement.setBigDecimal(6, attendance.weekdayOvertimeHours());
                    statement.setBigDecimal(7, attendance.restDayOvertimeHours());
                    statement.setBigDecimal(8, attendance.holidayOvertimeHours());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }

        String variableSql =
                """
                INSERT INTO variable_pay (employee_id, period, performance_bonus, other_allowance)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(variableSql)) {
            for (Employee employee : demo.employees()) {
                for (YearMonth month = YearMonth.from(employee.hireDate());
                        !month.isAfter(through);
                        month = month.plusMonths(1)) {
                    MonthlyVariablePay pay =
                            demo.findVariablePay(employee.employeeId(), month);
                    if (pay.performanceBonus().signum() == 0 && pay.otherAllowance().signum() == 0) {
                        continue;
                    }
                    statement.setString(1, pay.employeeId());
                    statement.setString(2, pay.period());
                    statement.setBigDecimal(3, pay.performanceBonus());
                    statement.setBigDecimal(4, pay.otherAllowance());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
        seedReviewPlaceholders(connection);
    }

    /** 演示考核记录，系数统一为 1，不改变既有核算口径。 */
    private void seedReviewPlaceholders(Connection connection) throws SQLException {
        if (count(connection, "performance_reviews") > 0) {
            return;
        }
        String period = latestPeriod(connection);
        if (period == null) {
            return;
        }
        Map<String, BigDecimalScore> scores = new LinkedHashMap<>();
        scores.put("EMP001", new BigDecimalScore("92.00", "交付稳定，负责核心链路"));
        scores.put("EMP002", new BigDecimalScore("88.00", "完成季度销售目标"));
        scores.put("EMP003", new BigDecimalScore("78.00", "产能达标，安全零事故"));
        scores.put("EMP004", new BigDecimalScore("90.00", "月结准确率 100%"));
        scores.put("EMP005", new BigDecimalScore("85.00", "入职后快速接手算法模块"));
        scores.put("EMP006", new BigDecimalScore("82.00", "招聘完成率达标"));
        String sql =
                dialect(
                        """
                        MERGE INTO performance_reviews
                            (employee_id, period, score, coefficient, review_comment, updated_at)
                        KEY(employee_id, period) VALUES (?, ?, ?, 1.0000, ?, ?)
                        """,
                        """
                        INSERT INTO performance_reviews
                            (employee_id, period, score, coefficient, review_comment, updated_at)
                        VALUES (?, ?, ?, 1.0000, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            score = VALUES(score),
                            coefficient = VALUES(coefficient),
                            review_comment = VALUES(review_comment),
                            updated_at = VALUES(updated_at)
                        """);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, BigDecimalScore> entry : scores.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, period);
                statement.setBigDecimal(3, entry.getValue().score());
                statement.setString(4, entry.getValue().comment());
                statement.setObject(5, LocalDateTime.now());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 部门与内置角色只在缺失时补齐，不覆盖管理台已经调整过的数据。 */
    private void seedIdentityData(Connection connection) throws SQLException {
        String departmentSql =
                dialect(
                        """
                        INSERT INTO departments (name, description, active, created_at)
                        SELECT ?, ?, TRUE, ? WHERE NOT EXISTS
                            (SELECT 1 FROM departments WHERE name = ?)
                        """,
                        """
                        INSERT INTO departments (name, description, active, created_at)
                        SELECT ?, ?, TRUE, ? FROM DUAL WHERE NOT EXISTS
                            (SELECT 1 FROM departments WHERE name = ?)
                        """);
        try (PreparedStatement statement = connection.prepareStatement(departmentSql)) {
            for (String name : List.of("研发部", "销售部", "财务部", "生产部", "人力资源部")) {
                statement.setString(1, name);
                statement.setString(2, "演示数据初始化");
                statement.setObject(3, LocalDateTime.now());
                statement.setString(4, name);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        INSERT INTO departments (name, description, active, created_at)
                        SELECT DISTINCT department, '由员工档案同步', TRUE, ?
                        FROM employees e
                        WHERE NOT EXISTS
                            (SELECT 1 FROM departments d WHERE d.name = e.department)
                        """)) {
            statement.setObject(1, LocalDateTime.now());
            statement.executeUpdate();
        }

        String roleSql =
                dialect(
                        """
                        INSERT INTO roles (code, name, description, permissions_json, builtin, created_at)
                        SELECT ?, ?, ?, ?, TRUE, ? WHERE NOT EXISTS
                            (SELECT 1 FROM roles WHERE code = ?)
                        """,
                        """
                        INSERT INTO roles (code, name, description, permissions_json, builtin, created_at)
                        SELECT ?, ?, ?, ?, TRUE, ? FROM DUAL WHERE NOT EXISTS
                            (SELECT 1 FROM roles WHERE code = ?)
                        """);
        Map<String, String> names =
                Map.of(
                        Permissions.ROLE_ADMIN, "系统管理员",
                        Permissions.ROLE_HR, "人事专员",
                        Permissions.ROLE_VIEWER, "只读访客");
        try (PreparedStatement statement = connection.prepareStatement(roleSql)) {
            for (Map.Entry<String, java.util.Set<String>> entry :
                    Permissions.builtinRolePermissions().entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, names.getOrDefault(entry.getKey(), entry.getKey()));
                statement.setString(3, "内置角色");
                statement.setString(4, JsonSupport.toJson(entry.getValue()));
                statement.setObject(5, LocalDateTime.now());
                statement.setString(6, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        // 内置角色不允许在管理台修改，其权限始终跟随代码定义，新增权限码才能对旧库生效。
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE roles SET permissions_json = ? WHERE code = ? AND builtin = TRUE")) {
            for (Map.Entry<String, java.util.Set<String>> entry :
                    Permissions.builtinRolePermissions().entrySet()) {
                statement.setString(1, JsonSupport.toJson(entry.getValue()));
                statement.setString(2, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 首次启动写入一条全局默认审批链，保证工资批次开箱即可提交审批。 */
    private void seedApprovalFlows(Connection connection) throws SQLException {
        String sql =
                dialect(
                        """
                        INSERT INTO approval_flows
                            (name, scope_type, scope_value, min_amount, max_amount, priority, enabled,
                             description, created_at, updated_at)
                        SELECT ?, 'GLOBAL', '*', 0, NULL, 100, TRUE, ?, ?, ?
                        WHERE NOT EXISTS (SELECT 1 FROM approval_flows)
                        """,
                        """
                        INSERT INTO approval_flows
                            (name, scope_type, scope_value, min_amount, max_amount, priority, enabled,
                             description, created_at, updated_at)
                        SELECT ?, 'GLOBAL', '*', 0, NULL, 100, TRUE, ?, ?, ? FROM DUAL
                        WHERE NOT EXISTS (SELECT 1 FROM approval_flows)
                        """);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setString(1, "默认审批链");
            statement.setString(2, "系统初始化：适用于未单独配置审批链的工资批次");
            statement.setObject(3, now);
            statement.setObject(4, now);
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String latestPeriod(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT MAX(period) FROM attendance")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Map<String, PayrollPolicy> demoCityPolicies() {
        PayrollPolicy base = PayrollPolicy.demoPolicy();
        Map<String, PayrollPolicy> policies = new LinkedHashMap<>();
        policies.put("北京", base.withSocialBounds(new java.math.BigDecimal("7500.00"),
                new java.math.BigDecimal("37500.00")));
        policies.put("上海", base.withSocialBounds(new java.math.BigDecimal("7384.00"),
                new java.math.BigDecimal("36921.00")));
        policies.put("深圳", base.withSocialBounds(new java.math.BigDecimal("4492.00"),
                new java.math.BigDecimal("27501.00")));
        policies.put("杭州", base.withSocialBounds(new java.math.BigDecimal("4812.00"),
                new java.math.BigDecimal("24930.00")));
        policies.put("默认", base);
        return policies;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record BigDecimalScore(java.math.BigDecimal score, String comment) {
        private BigDecimalScore(String score, String comment) {
            this(new java.math.BigDecimal(score), comment);
        }
    }
}
