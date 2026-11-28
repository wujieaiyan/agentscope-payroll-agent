package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.PayrollWebApplication;
import io.agentscope.examples.payroll.db.support.MappedStatementVerifier;
import io.agentscope.examples.payroll.health.PayrollReadinessHealthIndicator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P0-4 启动冒烟：DataSource / 事务管理器由本项目装配，Boot 的 JDBC 自动配置被排除。
 *
 * <p>Flyway 只能由 {@code PayrollDatabase.migrate()} 跑一次——两套迁移共用
 * {@code flyway_schema_history} 时会以重复版本失败，这是引入 DataSource Bean 后最容易踩的坑。
 */
@SpringBootTest(classes = PayrollWebApplication.class)
@Tag("integration")
class MybatisStartupSmokeTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:mybatis-smoke-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "false");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PayrollDatabase database;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private HealthContributorRegistry healthContributors;

    @Test
    @DisplayName("装配：唯一的 DataSource 是适配器，事务管理器是 DataSourceTransactionManager")
    void wiringUsesPayrollDatabase() throws SQLException {
        assertEquals(1, context.getBeansOfType(DataSource.class).size());
        PayrollDatabaseDataSource adapter = assertInstanceOf(PayrollDatabaseDataSource.class, dataSource);
        assertSame(database, adapter.database());

        DataSourceTransactionManager manager =
                assertInstanceOf(DataSourceTransactionManager.class, transactionManager);
        assertSame(dataSource, manager.getDataSource());

        // 非事务路径不改变 autoCommit：适配器直连 PayrollDatabase 归还/关闭的行为不变。
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    @DisplayName("MyBatis 装配：SqlSessionFactory 与启动语句数断言都在容器里")
    void mybatisStartupAssertionIsWired() {
        assertNotNull(context.getBean(SqlSessionFactory.class));
        assertNotNull(context.getBean(MappedStatementVerifier.class));
    }

    @Test
    @DisplayName("Boot 的 Flyway 自动配置被排除：容器内没有 Flyway / 迁移初始化器 Bean")
    void bootFlywayAutoconfigurationIsExcluded() {
        assertEquals(0, context.getBeansOfType(Flyway.class).size());
        assertFalse(context.containsBean("flywayInitializer"));
    }

    @Test
    @DisplayName("Flyway 只迁移一次：历史表无失败行、无重复版本")
    void flywayRanExactlyOnce() throws SQLException {
        String sql =
                """
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT "version") AS versions,
                       SUM(CASE WHEN "success" = FALSE THEN 1 ELSE 0 END) AS failed
                FROM "flyway_schema_history"
                WHERE "version" IS NOT NULL
                """;
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            long total = rs.getLong("total");
            long versions = rs.getLong("versions");
            long failed = rs.getLong("failed");
            assertEquals(0L, failed, "存在失败的迁移记录：" + failed);
            assertEquals(total, versions, "同一版本被迁移了多次");
            assertTrue(total >= 20, "迁移历史行数异常：" + total);
        }
    }

    @Test
    @DisplayName("健康检查形状不变：没有 db 组件，readiness 仍是 payrollReadiness")
    void healthShapeUnchanged() {
        Set<String> names = new HashSet<>();
        for (NamedContributor<HealthContributor> contributor : healthContributors) {
            names.add(contributor.getName());
        }
        assertFalse(names.contains("db"), "DataSource 不该新增 db 健康项：" + names);
        assertNull(healthContributors.getContributor("db"));
        assertNotNull(context.getBean(PayrollReadinessHealthIndicator.class));
        assertTrue(names.contains("payrollReadiness"), "readiness 组件缺失：" + names);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mybatis-smoke-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}