package io.agentscope.examples.payroll.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class PayrollReadinessHealthIndicatorTest {

    @Test
    @DisplayName("single 模式 Redis 不可达只降级详情，readiness 保持 UP")
    void singleDegradesRedisWithoutFailing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PayrollReadinessHealthIndicator indicator =
                indicator(
                        true,
                        new RedisHealthProbe.Summary(true, false, null, "连接失败"),
                        DeploymentMode.SINGLE,
                        null,
                        registry);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("UP", health.getDetails().get("db"));
        assertEquals("DEGRADED", health.getDetails().get("redis"));
        assertNull(health.getDetails().get("failing_dep"));
        assertEquals(1.0, gauge(registry, "payroll.readyz.status", "single", null));
        assertEquals(0.0, gauge(registry, "payroll.readyz.failing", "single", "redis"));
    }

    @Test
    @DisplayName("cluster 模式 Redis 不可达判定 DOWN 并标记失败依赖")
    void clusterFailsWhenRedisUnreachable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PayrollReadinessHealthIndicator indicator =
                indicator(
                        true,
                        new RedisHealthProbe.Summary(true, false, null, "连接失败"),
                        DeploymentMode.CLUSTER,
                        null,
                        registry);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("redis"));
        assertEquals("redis", health.getDetails().get("failing_dep"));
        assertEquals(0.0, gauge(registry, "payroll.readyz.status", "cluster", null));
        assertEquals(1.0, gauge(registry, "payroll.readyz.failing", "cluster", "redis"));
        assertEquals(0.0, gauge(registry, "payroll.readyz.failing", "cluster", "db"));
    }

    @Test
    @DisplayName("cluster 模式 AOF 关闭判定 DOWN")
    void clusterFailsWhenPersistenceDisabled() {
        PayrollReadinessHealthIndicator indicator =
                indicator(
                        true,
                        new RedisHealthProbe.Summary(true, true, Boolean.FALSE, "持久化未开启"),
                        DeploymentMode.CLUSTER,
                        null,
                        new SimpleMeterRegistry());

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("redis_persistence"));
    }

    @Test
    @DisplayName("cluster 模式持久化未知按 DOWN 处理，避免误判为可持久")
    void clusterFailsWhenPersistenceUnknown() {
        PayrollReadinessHealthIndicator indicator =
                indicator(
                        true,
                        new RedisHealthProbe.Summary(true, true, null, "持久化未知"),
                        DeploymentMode.CLUSTER,
                        null,
                        new SimpleMeterRegistry());

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals("UNKNOWN", indicator.health().getDetails().get("redis_persistence"));
    }

    @Test
    @DisplayName("single 模式 AOF 关闭只降级，不摘流")
    void singleKeepsUpWhenPersistenceDisabled() {
        PayrollReadinessHealthIndicator indicator =
                indicator(
                        true,
                        new RedisHealthProbe.Summary(true, true, Boolean.FALSE, "持久化未开启"),
                        DeploymentMode.SINGLE,
                        null,
                        new SimpleMeterRegistry());

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("redis"));
    }

    @Test
    @DisplayName("数据库不可用时无论部署模式都判定 DOWN")
    void databaseFailureFailsBothModes() {
        PayrollReadinessHealthIndicator single =
                indicator(
                        false,
                        new RedisHealthProbe.Summary(true, true, Boolean.TRUE, "可写"),
                        DeploymentMode.SINGLE,
                        null,
                        new SimpleMeterRegistry());
        PayrollReadinessHealthIndicator cluster =
                indicator(
                        false,
                        new RedisHealthProbe.Summary(true, true, Boolean.TRUE, "可写"),
                        DeploymentMode.CLUSTER,
                        null,
                        new SimpleMeterRegistry());

        assertEquals(Status.DOWN, single.health().getStatus());
        assertEquals("db", single.health().getDetails().get("failing_dep"));
        assertEquals(Status.DOWN, cluster.health().getStatus());
        assertEquals("db", cluster.health().getDetails().get("failing_dep"));
    }

    @Test
    @DisplayName("仅声明 db 时不读取 Redis 探针")
    void skipsRedisWhenNotConfigured() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        PayrollReadinessHealthIndicator indicator =
                indicator(true, probe, DeploymentMode.CLUSTER, "db", new SimpleMeterRegistry());

        Health health = indicator.health();

        assertTrue(Status.UP.equals(health.getStatus()));
        assertFalse(health.getDetails().containsKey("redis"));
        verify(probe, never()).summary();
    }

    private static PayrollReadinessHealthIndicator indicator(
            boolean databaseUp,
            RedisHealthProbe probe,
            DeploymentMode mode,
            String deps,
            SimpleMeterRegistry registry) {
        return new PayrollReadinessHealthIndicator(
                database(databaseUp), probe, mode, deps, registry);
    }

    private static PayrollReadinessHealthIndicator indicator(
            boolean databaseUp,
            RedisHealthProbe.Summary summary,
            DeploymentMode mode,
            String deps,
            SimpleMeterRegistry registry) {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        when(probe.summary()).thenReturn(summary);
        return indicator(databaseUp, probe, mode, deps, registry);
    }

    private static PayrollDatabase database(boolean valid) {
        PayrollDatabase database = mock(PayrollDatabase.class);
        Connection connection = mock(Connection.class);
        try {
            when(connection.isValid(2)).thenReturn(valid);
            when(database.connection()).thenReturn(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return database;
    }

    private static double gauge(
            SimpleMeterRegistry registry, String name, String mode, String dep) {
        var gauges = registry.find(name).tag("deployment.mode", mode);
        if (dep != null) {
            gauges = gauges.tag("dep", dep);
        }
        return gauges.gauge().value();
    }
}
