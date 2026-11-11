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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * 薪酬管理台数据库：默认 H2 文件库，部门部署可切换 MySQL。
 *
 * <p>H2 使用内联 DDL 幂等建表；MySQL 使用 Flyway（classpath:db/migration/mysql）版本化建表，
 * 并由 HikariCP 提供连接池。两种库共用同一套仓储代码，方言差异集中在 upsert 与种子 SQL。
 *
 * <p>默认文件为 {@code ./data/payroll.mv.db}。首次启动自动建表，并把 classpath 中的演示数据
 * 迁移进库；之后所有修改都以数据库为准。
 */
public final class PayrollDatabase {

    public static final String DEFAULT_URL =
            "jdbc:h2:file:./data/payroll;DB_CLOSE_ON_EXIT=FALSE";
    private static final String DEFAULT_USER = "sa";
    private static final String DEFAULT_PASSWORD = "";

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS departments (
                department_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(64) NOT NULL UNIQUE,
                description VARCHAR(200) NOT NULL DEFAULT '',
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS roles (
                code VARCHAR(32) PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                description VARCHAR(200) NOT NULL DEFAULT '',
                permissions_json CLOB NOT NULL,
                builtin BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS app_users (
                user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(32) NOT NULL UNIQUE,
                display_name VARCHAR(64) NOT NULL,
                password_hash VARCHAR(100) NOT NULL,
                role_code VARCHAR(32) NOT NULL,
                department_id BIGINT,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_login_at TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                session_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                title VARCHAR(120) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id BIGINT NOT NULL,
                role VARCHAR(16) NOT NULL,
                content CLOB NOT NULL,
                tools_json CLOB,
                model VARCHAR(128),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            ALTER TABLE chat_sessions
                ADD COLUMN IF NOT EXISTS context_summary CLOB
            """,
            """
            ALTER TABLE chat_sessions
                ADD COLUMN IF NOT EXISTS context_turns INT NOT NULL DEFAULT 0
            """,
            """
            ALTER TABLE chat_sessions
                ADD COLUMN IF NOT EXISTS persona_key VARCHAR(128)
            """,
            """
            ALTER TABLE chat_messages
                ADD COLUMN IF NOT EXISTS routing_json CLOB
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_sessions_user
                ON chat_sessions (user_id, updated_at)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_messages_session
                ON chat_messages (session_id, message_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_tool_traces (
                trace_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id BIGINT NOT NULL,
                session_id BIGINT NOT NULL,
                tool_call_id VARCHAR(128),
                tool_name VARCHAR(64) NOT NULL,
                agent VARCHAR(32),
                args_json CLOB,
                result_json CLOB,
                truncated BOOLEAN NOT NULL DEFAULT FALSE,
                result_state VARCHAR(16),
                duration_ms BIGINT NOT NULL DEFAULT 0,
                employees_json CLOB,
                period VARCHAR(16),
                department VARCHAR(64),
                amounts_json CLOB,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_tool_traces_message
                ON chat_tool_traces (message_id, trace_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_tool_traces_session
                ON chat_tool_traces (session_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS employees (
                employee_id VARCHAR(32) PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                department VARCHAR(64) NOT NULL,
                job_title VARCHAR(64),
                base_salary DECIMAL(14,2) NOT NULL,
                position_allowance DECIMAL(14,2) NOT NULL DEFAULT 0,
                housing_fund_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
                social_insurance_base DECIMAL(14,2),
                special_additional_deduction DECIMAL(14,2) NOT NULL DEFAULT 0,
                hire_date DATE NOT NULL,
                bank_account_masked VARCHAR(64),
                email VARCHAR(128),
                city VARCHAR(32) NOT NULL DEFAULT '默认',
                active BOOLEAN NOT NULL DEFAULT TRUE,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance (
                employee_id VARCHAR(32) NOT NULL,
                period VARCHAR(7) NOT NULL,
                personal_leave_days DECIMAL(6,2) NOT NULL DEFAULT 0,
                sick_leave_days DECIMAL(6,2) NOT NULL DEFAULT 0,
                absent_days DECIMAL(6,2) NOT NULL DEFAULT 0,
                weekday_overtime_hours DECIMAL(8,2) NOT NULL DEFAULT 0,
                rest_day_overtime_hours DECIMAL(8,2) NOT NULL DEFAULT 0,
                holiday_overtime_hours DECIMAL(8,2) NOT NULL DEFAULT 0,
                PRIMARY KEY (employee_id, period)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS variable_pay (
                employee_id VARCHAR(32) NOT NULL,
                period VARCHAR(7) NOT NULL,
                performance_bonus DECIMAL(14,2) NOT NULL DEFAULT 0,
                other_allowance DECIMAL(14,2) NOT NULL DEFAULT 0,
                PRIMARY KEY (employee_id, period)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS performance_reviews (
                employee_id VARCHAR(32) NOT NULL,
                period VARCHAR(7) NOT NULL,
                score DECIMAL(6,2) NOT NULL,
                coefficient DECIMAL(8,4) NOT NULL,
                review_comment VARCHAR(500),
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (employee_id, period)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS city_policies (
                city VARCHAR(32) PRIMARY KEY,
                policy_json CLOB NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS payroll_runs (
                run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                period VARCHAR(7) NOT NULL,
                scope VARCHAR(64) NOT NULL,
                status VARCHAR(16) NOT NULL,
                employee_count INT NOT NULL,
                total_gross DECIMAL(16,2) NOT NULL,
                total_deductions DECIMAL(16,2) NOT NULL,
                total_net DECIMAL(16,2) NOT NULL,
                total_employer_cost DECIMAL(16,2) NOT NULL,
                excel_path VARCHAR(512),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                finalized_at TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS payroll_details (
                run_id BIGINT NOT NULL,
                employee_id VARCHAR(32) NOT NULL,
                employee_name VARCHAR(64),
                department VARCHAR(64),
                city VARCHAR(32),
                email VARCHAR(128),
                adjustment_json CLOB,
                result_json CLOB NOT NULL,
                email_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                email_reason VARCHAR(500),
                PRIMARY KEY (run_id, employee_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS payroll_run_failures (
                run_id BIGINT NOT NULL,
                employee_id VARCHAR(32) NOT NULL,
                message VARCHAR(500),
                PRIMARY KEY (run_id, employee_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS email_deliveries (
                delivery_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id BIGINT NOT NULL,
                employee_id VARCHAR(32) NOT NULL,
                email VARCHAR(128),
                status VARCHAR(24) NOT NULL,
                reason VARCHAR(500),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS approval_flows (
                flow_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                scope_type VARCHAR(16) NOT NULL,
                scope_value VARCHAR(64) NOT NULL,
                min_amount DECIMAL(16,2) NOT NULL DEFAULT 0,
                max_amount DECIMAL(16,2),
                priority INT NOT NULL DEFAULT 100,
                enabled BOOLEAN NOT NULL DEFAULT TRUE,
                description VARCHAR(200) NOT NULL DEFAULT '',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS approval_flow_steps (
                step_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                flow_id BIGINT NOT NULL,
                step_order INT NOT NULL,
                name VARCHAR(64) NOT NULL,
                approver_type VARCHAR(16) NOT NULL,
                approver_value VARCHAR(64) NOT NULL,
                min_amount DECIMAL(16,2),
                max_amount DECIMAL(16,2),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_approval_flow_steps_flow
                ON approval_flow_steps (flow_id, step_order)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS workflow_steps_json CLOB
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS current_step INT NOT NULL DEFAULT 0
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approval_flow_id BIGINT
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approval_flow_name VARCHAR(64)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS submitted_by_id BIGINT
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS submitted_by_name VARCHAR(64)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS submit_comment VARCHAR(500)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approved_by_id BIGINT
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approved_by_name VARCHAR(64)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS approval_comment VARCHAR(500)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS rejected_by_id BIGINT
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS rejected_by_name VARCHAR(64)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500)
            """,
            """
            ALTER TABLE payroll_runs
                ADD COLUMN IF NOT EXISTS snapshot_hash VARCHAR(64)
            """,
            """
            CREATE TABLE IF NOT EXISTS payroll_approval_events (
                event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id BIGINT NOT NULL,
                action VARCHAR(16) NOT NULL,
                actor_id BIGINT,
                actor_name VARCHAR(64),
                flow_id BIGINT,
                flow_name VARCHAR(64),
                comment VARCHAR(500),
                snapshot_hash VARCHAR(64),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            ALTER TABLE payroll_approval_events
                ADD COLUMN IF NOT EXISTS step_order INT
            """,
            """
            ALTER TABLE payroll_approval_events
                ADD COLUMN IF NOT EXISTS step_name VARCHAR(64)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_approval_events_run
                ON payroll_approval_events (run_id, event_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_approval_flows_scope
                ON approval_flows (scope_type, scope_value, enabled)
            """,
            """
            CREATE TABLE IF NOT EXISTS audit_log (
                audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                actor_id BIGINT,
                actor_name VARCHAR(64),
                action VARCHAR(128) NOT NULL,
                resource_type VARCHAR(32),
                resource_id VARCHAR(256),
                before_json CLOB,
                after_json CLOB,
                payload_json CLOB,
                ip VARCHAR(64),
                user_agent VARCHAR(256),
                result VARCHAR(16) NOT NULL,
                status_code INT,
                message VARCHAR(500),
                trace_id VARCHAR(64)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_audit_occurred
                ON audit_log (occurred_at)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_audit_actor
                ON audit_log (actor_id, occurred_at)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_audit_resource
                ON audit_log (resource_type, resource_id, occurred_at)
            """,
            // 动态团队数据地基。run_mode / agent_role / comment_text 避开 H2 关键字。
            """
            CREATE TABLE IF NOT EXISTS agent_team_runs (
                run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                plan_id VARCHAR(64) NOT NULL,
                session_id BIGINT NOT NULL,
                message_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                run_mode VARCHAR(16) NOT NULL,
                task_type VARCHAR(32),
                template_id VARCHAR(64),
                plan_json CLOB NOT NULL,
                catalog_snapshot_json CLOB,
                score_snapshot_json CLOB,
                status VARCHAR(24) NOT NULL,
                fallback_reason VARCHAR(256),
                baseline_run_id BIGINT,
                fallback_of_run_id BIGINT,
                retry_of_run_id BIGINT,
                is_primary BOOLEAN NOT NULL DEFAULT FALSE,
                total_ms BIGINT NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                closed_at TIMESTAMP,
                archived_at TIMESTAMP,
                deleted_at TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_run_records (
                record_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id BIGINT NOT NULL,
                node_id VARCHAR(64) NOT NULL,
                stage_key VARCHAR(64),
                agent_id VARCHAR(64),
                agent_role VARCHAR(32),
                status VARCHAR(24) NOT NULL,
                tool_calls INT NOT NULL DEFAULT 0,
                tool_errors INT NOT NULL DEFAULT 0,
                validation_passed BOOLEAN,
                output_chars INT NOT NULL DEFAULT 0,
                duration_ms BIGINT NOT NULL DEFAULT 0,
                trace_ids_json CLOB,
                input_digest VARCHAR(64),
                message_json CLOB,
                error_message VARCHAR(512),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_feedback (
                feedback_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id BIGINT NOT NULL,
                session_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                rating VARCHAR(8) NOT NULL,
                tags_json CLOB,
                comment_text VARCHAR(1000),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_chat_feedback_message_user UNIQUE (message_id, user_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_action_proposals (
                proposal_id VARCHAR(36) PRIMARY KEY,
                user_id BIGINT NOT NULL,
                session_id BIGINT NOT NULL,
                message_id BIGINT NOT NULL DEFAULT 0,
                agent_path VARCHAR(32),
                tool_name VARCHAR(128) NOT NULL,
                params_json CLOB,
                summary VARCHAR(500) NOT NULL,
                risk_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                safety_source VARCHAR(32),
                status VARCHAR(16) NOT NULL,
                idempotency_key VARCHAR(128),
                snapshot_hash VARCHAR(64),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP,
                confirmed_by BIGINT,
                confirmed_at TIMESTAMP,
                executed_at TIMESTAMP,
                result_json CLOB,
                error_message VARCHAR(500)
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_action_proposals_idempotency
                ON chat_action_proposals (user_id, idempotency_key)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_action_proposals_session
                ON chat_action_proposals (session_id, status)
            """,
            """
            ALTER TABLE chat_tool_traces ADD COLUMN IF NOT EXISTS run_id BIGINT
            """,
            """
            ALTER TABLE chat_tool_traces ADD COLUMN IF NOT EXISTS node_id VARCHAR(64)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_agent_team_runs_message
                ON agent_team_runs (message_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_agent_team_runs_session
                ON agent_team_runs (session_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_agent_team_runs_primary
                ON agent_team_runs (message_id, is_primary)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_agent_run_records_run
                ON agent_run_records (run_id, node_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_agent_run_records_agent
                ON agent_run_records (agent_id, created_at)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_feedback_message
                ON chat_feedback (message_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_chat_tool_traces_run
                ON chat_tool_traces (run_id, node_id)
            """);

    private static final Map<String, PayrollPolicy> DEMO_CITY_POLICIES = demoCityPolicies();

    private final String url;
    private final String user;
    private final String password;
    private final boolean mysql;
    private final HikariDataSource pool;

    private PayrollDatabase(String url, String user, String password, int poolSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.mysql = url.startsWith("jdbc:mysql:");
        this.pool = mysql ? createPool(url, user, password, poolSize) : null;
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
        if (mysql) {
            migrateMysql();
        } else {
            ensureDirectory();
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            if (!mysql) {
                try (Statement statement = connection.createStatement()) {
                    for (String ddl : SCHEMA) {
                        statement.execute(ddl);
                    }
                    for (String ddl : io.agentscope.examples.payroll.kb.KbSchema.DDL) {
                        statement.execute(ddl);
                    }
                }
            }
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

    /** MySQL 表结构由 Flyway 版本化管理，避免内联 DDL 与已有库结构漂移。 */
    private void migrateMysql() {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(pool)
                        .locations("classpath:db/migration/mysql")
                        .table("flyway_schema_history")
                        .load();
        try {
            flyway.migrate();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "MySQL 表结构迁移失败：目标库必须为空库或已由 Flyway 管理；"
                            + "请勿手工建表，也不要指向已有业务表的库。原始错误："
                            + e.getMessage(),
                    e);
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
