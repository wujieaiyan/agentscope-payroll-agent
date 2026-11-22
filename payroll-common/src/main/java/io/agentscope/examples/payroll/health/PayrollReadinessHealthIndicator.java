package io.agentscope.examples.payroll.health;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * readiness 判定：默认只看 DB 与 Redis，ES/Nacos 只出现在各自的 Actuator 详情里。
 *
 * <p>cluster 模式下 Redis 不可达、AOF 关闭或无法确认持久化都会让节点从负载均衡摘除；
 * single 模式保留本地降级，Redis 问题只降级详情并告警，不阻断流量。
 */
public final class PayrollReadinessHealthIndicator implements HealthIndicator {

    private final PayrollDatabase database;
    private final RedisHealthProbe redis;
    private final DeploymentMode mode;
    private final Set<String> deps;
    private final AtomicInteger status = new AtomicInteger(1);
    private final AtomicInteger dbFailing = new AtomicInteger(0);
    private final AtomicInteger redisFailing = new AtomicInteger(0);

    public PayrollReadinessHealthIndicator(
            PayrollDatabase database,
            RedisHealthProbe redis,
            DeploymentMode mode,
            String readinessDeps,
            MeterRegistry registry) {
        this.database = database;
        this.redis = redis;
        this.mode = mode == null ? DeploymentMode.SINGLE : mode;
        this.deps = parseDeps(readinessDeps);
        registerGauges(registry);
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean failing = false;
        String failingDep = "";

        String dbStatus = "SKIPPED";
        if (deps.contains("db")) {
            dbStatus = checkDatabase();
            builder.withDetail("db", dbStatus);
            dbFailing.set("UP".equals(dbStatus) ? 0 : 1);
            if (!"UP".equals(dbStatus)) {
                failing = true;
                failingDep = "db";
                builder.down();
            }
        }

        String redisStatus = "SKIPPED";
        if (deps.contains("redis")) {
            RedisHealthProbe.Summary summary = redis.summary();
            boolean required = mode.cluster();
            redisStatus = redisStatus(summary, required);
            builder.withDetail("redis", redisStatus)
                    .withDetail(
                            "redis_persistence",
                            summary.persistence() == null
                                    ? "UNKNOWN"
                                    : summary.persistence() ? "UP" : "DOWN");
            redisFailing.set(required && !"UP".equals(redisStatus) ? 1 : 0);
            if (required && !"UP".equals(redisStatus)) {
                failing = true;
                if (failingDep.isEmpty()) {
                    failingDep = "redis";
                }
                builder.down();
            }
        }

        status.set(failing ? 0 : 1);
        builder.withDetail("deployment_mode", mode.value())
                .withDetail("deps", String.join(",", deps));
        if (failing) {
            builder.withDetail("failing_dep", failingDep);
        }
        return builder.build();
    }

    private String checkDatabase() {
        try (Connection connection = database.connection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (SQLException | RuntimeException e) {
            return "DOWN";
        }
    }

    /** single 下 Redis 问题只标 DEGRADED，保持 200；cluster 下任何不确定都按 DOWN。 */
    private static String redisStatus(RedisHealthProbe.Summary summary, boolean required) {
        if (!summary.configured()) {
            return required ? "DOWN" : "SKIPPED";
        }
        if (!summary.reachable()) {
            return required ? "DOWN" : "DEGRADED";
        }
        if (Boolean.FALSE.equals(summary.persistence())) {
            return required ? "DOWN" : "DEGRADED";
        }
        if (summary.persistence() == null) {
            return required ? "DOWN" : "UNKNOWN";
        }
        return "UP";
    }

    private void registerGauges(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder("payroll.readyz.status", status, AtomicInteger::doubleValue)
                .description("readyz 聚合状态（1 UP / 0 DOWN）")
                .tag("deployment.mode", mode.value())
                .register(registry);
        Gauge.builder("payroll.readyz.failing", dbFailing, AtomicInteger::doubleValue)
                .description("readiness 依赖是否失败（1 失败 / 0 正常）")
                .tag("deployment.mode", mode.value())
                .tag("dep", "db")
                .register(registry);
        Gauge.builder("payroll.readyz.failing", redisFailing, AtomicInteger::doubleValue)
                .description("readiness 依赖是否失败（1 失败 / 0 正常）")
                .tag("deployment.mode", mode.value())
                .tag("dep", "redis")
                .register(registry);
    }

    private static Set<String> parseDeps(String raw) {
        Set<String> parsed = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    parsed.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed.add("db");
            parsed.add("redis");
        }
        return parsed;
    }
}
