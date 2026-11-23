package io.agentscope.examples.payroll.health;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 健康探针：每 30 秒做一次写探针，并尽力确认持久化是否开启。
 *
 * <p>持久化判定优先 {@code CONFIG GET appendonly}，被托管 Redis 禁用配置命令时退化为
 * {@code INFO persistence} 的 {@code aof_enabled}；两者都拿不到只告警，不阻断启动。
 * single 模式只影响详情与告警，cluster 模式把不可达或持久化关闭视为 readiness 失败。
 */
public final class RedisHealthProbe implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthProbe.class);
    private static final String PROBE_KEY_PREFIX = "payroll:health:probe:";
    private static final Duration PROBE_TTL = Duration.ofSeconds(30);
    private static final long INTERVAL_SECONDS = 30;

    private final StringRedisTemplate redis;
    private final DeploymentMode mode;
    private final ScheduledExecutorService scheduler;

    private volatile boolean reachable;
    /** {@code null} 表示无法判定（CONFIG 与 INFO 都不可用）。 */
    private volatile Boolean persistence;
    private volatile String detail = "未探测";
    private volatile boolean warnedUnknown;

    public RedisHealthProbe(
            StringRedisTemplate redis, DeploymentMode mode, MeterRegistry registry) {
        this.redis = redis;
        this.mode = mode == null ? DeploymentMode.SINGLE : mode;
        if (redis == null) {
            this.detail = "未配置 Redis";
            registerGauge(registry);
            this.scheduler = null;
            return;
        }
        registerGauge(registry);
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "payroll-redis-health");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleWithFixedDelay(
                this::probeSafely, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 当前快照；readiness 只在调用时读取，不触发额外 IO。 */
    public Summary summary() {
        return new Summary(redis != null, reachable, persistence, detail);
    }

    private void registerGauge(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder("payroll.redis.persistence.ok", this, RedisHealthProbe::probeValue)
                .description("Redis 持久化是否开启（1 开启/未知，0 关闭）")
                .tag("deployment.mode", mode.value())
                .register(registry);
    }

    private static double probeValue(RedisHealthProbe probe) {
        Boolean value = probe.persistence;
        return Boolean.FALSE.equals(value) ? 0.0 : 1.0;
    }

    private void probeSafely() {
        try {
            probe();
        } catch (RuntimeException e) {
            log.warn("[健康] Redis 探针异常：{}", e.getMessage());
        }
    }

    private void probe() {
        String key = PROBE_KEY_PREFIX + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "1", PROBE_TTL);
            redis.delete(key);
            reachable = true;
            detail = "可写";
        } catch (RuntimeException e) {
            if (reachable || !"连接失败".equals(detail)) {
                log.warn("[健康] Redis 写探针失败：{}", e.getMessage());
            }
            reachable = false;
            detail = "连接失败";
            return;
        }
        Boolean current = detectPersistence();
        persistence = current;
        if (current == null) {
            if (!warnedUnknown) {
                warnedUnknown = true;
                log.warn("[健康] 无法确认 Redis 持久化状态（CONFIG/INFO 均不可用）");
            }
            detail = "持久化未知";
            return;
        }
        warnedUnknown = false;
        detail = current ? "持久化已开启" : "持久化未开启";
        if (!current) {
            String message =
                    mode.cluster()
                            ? "[健康] cluster 模式下 Redis 未开启 AOF，readyz 将保持 DOWN"
                            : "[健康] Redis 未开启 AOF：单机仅告警，建议在部门部署开启 appendonly";
            log.warn(message);
        }
    }

    private Boolean detectPersistence() {
        try {
            Properties config =
                    redis.execute(
                            connection ->
                                    connection.serverCommands().getConfig("appendonly"),
                            true);
            String value = config == null ? null : config.getProperty("appendonly");
            if (value != null && !value.isBlank()) {
                return "yes".equalsIgnoreCase(value.trim());
            }
        } catch (RuntimeException e) {
            log.debug("[健康] CONFIG GET appendonly 不可用，改用 INFO persistence：{}", e.getMessage());
        }
        try {
            Properties info =
                    redis.execute(
                            connection -> connection.serverCommands().info("persistence"), true);
            if (info != null) {
                String aof = info.getProperty("aof_enabled");
                if (aof != null && !aof.isBlank()) {
                    return "1".equals(aof.trim());
                }
            }
        } catch (RuntimeException e) {
            log.debug("[健康] INFO persistence 不可用：{}", e.getMessage());
        }
        return null;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 探针快照：configured 区分“未配置 Redis”和“配置了但不可用”。 */
    public record Summary(
            boolean configured, boolean reachable, Boolean persistence, String detail) {}
}
