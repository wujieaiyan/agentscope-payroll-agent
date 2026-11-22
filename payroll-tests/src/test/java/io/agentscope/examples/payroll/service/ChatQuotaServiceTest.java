package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ChatQuotaServiceTest {

    private static final long USER = 7L;

    private FakeRedis redis;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        TurnMetrics.useRegistry(null);
    }

    @Test
    @DisplayName("未超限时原子脚本返回许可，计数跨实例共享")
    void acquiresThroughLua() {
        redis.result = 2L;
        ChatQuotaService service = single("node-a", 2);

        assertTrue(service.tryAcquire(USER));
    }

    @Test
    @DisplayName("脚本返回 0 表示超限，同时累计拒绝指标")
    void rejectsOverLimit() {
        redis.result = 0L;
        ChatQuotaService service = single("node-a", 1);

        assertFalse(service.tryAcquire(USER));
        assertEquals(
                1.0, registry.find(TurnMetrics.QUOTA_REJECTED).counter().count());
    }

    @Test
    @DisplayName("许可字段带后端标记与配额 TTL，释放只回收本后端许可")
    void recordsBackendMarkerAndTtl() {
        redis.result = 1L;
        ChatQuotaService service =
                new ChatQuotaService(
                        redis, 2, Duration.ofMinutes(35), DeploymentMode.SINGLE, "node-a");
        assertTrue(service.tryAcquire(USER));

        assertEquals(List.of("payroll:chat:active:" + USER), redis.lastKeys);
        assertTrue(redis.lastField.startsWith("permit:"), redis.lastField);
        assertEquals("node-a", redis.lastBackend);
        assertEquals(
                Long.toString(Duration.ofMinutes(35).toMillis()), redis.lastTtl);
        assertEquals(Integer.toString(2), redis.lastMax);

        service.release(USER);
        assertEquals("permit:", redis.lastReleasePrefix);
        assertEquals("node-a", redis.lastReleaseBackend);
    }

    @Test
    @DisplayName("single 模式 Redis 故障回退进程内计数并仍受上限约束")
    void fallsBackToLocalInSingleMode() {
        redis.failure = new RuntimeException("redis down");
        ChatQuotaService service = single("node-a", 1);

        assertTrue(service.tryAcquire(USER));
        assertFalse(service.tryAcquire(USER));
        service.release(USER);
        assertTrue(service.tryAcquire(USER));
    }

    @Test
    @DisplayName("显式 deny 时 single 模式也快速失败，不再回退本地计数")
    void failsFastWhenConfiguredDeny() {
        redis.failure = new RuntimeException("redis down");
        ChatQuotaService service =
                new ChatQuotaService(
                        redis, 1, Duration.ofMinutes(35), DeploymentMode.SINGLE, "node-a", true);

        assertThrows(PayrollUnavailableException.class, () -> service.tryAcquire(USER));
    }

    @Test
    @DisplayName("cluster 模式 Redis 故障按 503 快速失败")
    void failsFastInClusterMode() {
        redis.failure = new RuntimeException("redis down");
        ChatQuotaService service =
                new ChatQuotaService(
                        redis, 1, Duration.ofMinutes(35), DeploymentMode.CLUSTER, "node-a");

        assertThrows(PayrollUnavailableException.class, () -> service.tryAcquire(USER));
    }

    @Test
    @DisplayName("cluster 模式缺少 Redis 时拒绝启动")
    void clusterRequiresRedis() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ChatQuotaService(
                                null, 1, Duration.ofMinutes(35), DeploymentMode.CLUSTER, "node-a"));
    }

    @Test
    @DisplayName("cluster 即使配置 local 也不回退：构造后仍快速失败")
    void clusterIgnoresLocalFailMode() {
        redis.failure = new RuntimeException("redis down");
        ChatQuotaService service =
                new ChatQuotaService(
                        redis, 1, Duration.ofMinutes(35), DeploymentMode.CLUSTER, "node-a", false);

        assertThrows(PayrollUnavailableException.class, () -> service.tryAcquire(USER));
        assertThrows(PayrollUnavailableException.class, () -> service.tryAcquire(USER));
    }

    private ChatQuotaService single(String backend, int maxConcurrent) {
        return new ChatQuotaService(
                redis, maxConcurrent, Duration.ofMinutes(35), DeploymentMode.SINGLE, backend);
    }

    /** 只覆盖 Lua 执行入口的替身：不连接 Redis，按脚本参数记录调用。 */
    private static final class FakeRedis extends StringRedisTemplate {

        private Long result = 1L;
        private RuntimeException failure;
        private List<String> lastKeys;
        private String lastMax;
        private String lastField;
        private String lastBackend;
        private String lastTtl;
        private String lastReleasePrefix;
        private String lastReleaseBackend;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKeys = keys;
            if (failure != null) {
                throw failure;
            }
            if (args.length == 4) {
                lastMax = (String) args[0];
                lastField = (String) args[1];
                lastBackend = (String) args[2];
                lastTtl = (String) args[3];
            } else if (args.length == 2) {
                lastReleasePrefix = (String) args[0];
                lastReleaseBackend = (String) args[1];
            }
            return (T) result;
        }
    }
}
