package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollRouteCacheTest {

    private static final PayrollRouteCache.Entry ENTRY =
            new PayrollRouteCache.Entry(RouteDecisionTarget.AGENT, "agent-1", "命中核算意图", 0.86);

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(registry);
    }

    @Test
    @DisplayName("连续两次一致才写缓存，第三次直接命中")
    void writesOnlyAfterTwoConsecutiveMatches() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store, Duration.ofDays(7));
        PayrollRouteCache.DecisionKey key = key(PayrollRouteBucket.CALC, "sig-a");

        assertFalse(cache.record(key, ENTRY));
        assertTrue(cache.lookup(key).isEmpty());
        assertTrue(cache.record(key, ENTRY));
        assertEquals(ENTRY, cache.lookup(key).orElseThrow());
        assertTrue(store.keys().stream().anyMatch(k -> k.startsWith(PayrollRouteCache.KEY_PREFIX)));
    }

    @Test
    @DisplayName("两次结果不同时只更新暂存，不写决策缓存")
    void differingDecisionsNeverWrite() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store);
        PayrollRouteCache.DecisionKey key = key(PayrollRouteBucket.QUERY, "sig-a");

        assertFalse(cache.record(key, ENTRY));
        assertFalse(
                cache.record(
                        key,
                        new PayrollRouteCache.Entry(
                                RouteDecisionTarget.TEAM, "", "改走团队", 0.7)));
        assertTrue(cache.lookup(key).isEmpty());
    }

    @Test
    @DisplayName("payroll_action 桶任何情况下都不缓存")
    void actionBucketIsNeverCached() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store);
        PayrollRouteCache.DecisionKey key = key(PayrollRouteBucket.ACTION, "sig-a");

        assertFalse(cache.record(key, ENTRY));
        assertFalse(cache.record(key, ENTRY));
        assertTrue(cache.lookup(key).isEmpty());
        assertTrue(cache.peek(key).isEmpty());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    @DisplayName("键含目录/流程/角色/团队/模型签名，任一变化都会换键")
    void decisionKeyCoversAllSignatureParts() {
        PayrollRouteCache.DecisionKey base = key(PayrollRouteBucket.CALC, "sig-a");
        assertNotEquals(base.cacheKey(), key(PayrollRouteBucket.POLICY, "sig-a").cacheKey());
        assertNotEquals(base.cacheKey(), key(PayrollRouteBucket.CALC, "sig-b").cacheKey());
        assertNotEquals(
                base.cacheKey(),
                new PayrollRouteCache.DecisionKey(
                                PayrollRouteBucket.CALC, "sig-a", "flow-1", "", true, "model")
                        .cacheKey());
        assertNotEquals(
                base.cacheKey(),
                new PayrollRouteCache.DecisionKey(
                                PayrollRouteBucket.CALC, "sig-a", "", "agent-9", true, "model")
                        .cacheKey());
        assertNotEquals(
                base.cacheKey(),
                new PayrollRouteCache.DecisionKey(
                                PayrollRouteBucket.CALC, "sig-a", "", "", false, "model")
                        .cacheKey());
        assertNotEquals(
                base.cacheKey(),
                new PayrollRouteCache.DecisionKey(
                                PayrollRouteBucket.CALC, "sig-a", "", "", true, "model-2")
                        .cacheKey());
        assertEquals(32, PayrollRouteCache.sha256("x").length());
    }

    @Test
    @DisplayName("签名变化时按首个变化维度记录失效原因")
    void recordsInvalidationReason() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store);
        double catalog = counter("catalog");
        double model = counter("model");

        cache.lookup(key(PayrollRouteBucket.CALC, "sig-a"));
        cache.lookup(key(PayrollRouteBucket.CALC, "sig-b"));
        cache.lookup(
                new PayrollRouteCache.DecisionKey(
                        PayrollRouteBucket.CALC, "sig-b", "", "", true, "model-2"));

        assertEquals(catalog + 1, counter("catalog"), 0.0001);
        assertEquals(model + 1, counter("model"), 0.0001);
    }

    @Test
    @DisplayName("shadow 只读探针不写暂存与元数据")
    void peekDoesNotWriteAnything() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store);

        assertTrue(cache.peek(key(PayrollRouteBucket.CALC, "sig-a")).isEmpty());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    @DisplayName("Redis 不可用时 fail-open，不抛异常也不回退本地缓存")
    void storeFailureFailsOpen() {
        PayrollRouteCache cache =
                new PayrollRouteCache(
                        new PayrollRouteCache.Store() {
                            @Override
                            public String get(String key) {
                                throw new IllegalStateException("redis down");
                            }

                            @Override
                            public void set(String key, String value, Duration ttl) {
                                throw new IllegalStateException("redis down");
                            }
                        });

        PayrollRouteCache.DecisionKey key = key(PayrollRouteBucket.CALC, "sig-a");
        assertTrue(cache.lookup(key).isEmpty());
        assertTrue(cache.peek(key).isEmpty());
        assertFalse(cache.record(key, ENTRY));
    }

    @Test
    @DisplayName("追问指代或不超过 20 字的问句不可复用缓存")
    void unsafeQuestionsAreDetected() {
        assertTrue(KbRoutingPolicy.routeCacheUnsafe("算工资", 20));
        assertTrue(KbRoutingPolicy.routeCacheUnsafe("上面提到的薪酬结果再算一遍", 20));
        assertTrue(KbRoutingPolicy.routeCacheUnsafe("", 20));
        assertFalse(
                KbRoutingPolicy.routeCacheUnsafe(
                        "请帮我计算一下本部门本月所有员工的应发工资合计金额", 20));
    }

    private static PayrollRouteCache.DecisionKey key(String bucket, String signature) {
        return new PayrollRouteCache.DecisionKey(bucket, signature, "", "", true, "model");
    }

    private double counter(String reason) {
        Counter counter =
                registry.find(PayrollRouteCache.INVALID_METRIC).tag("reason", reason).counter();
        return counter == null ? 0D : counter.count();
    }

    private static final class MemoryStore implements PayrollRouteCache.Store {

        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            values.put(key, value);
        }

        java.util.Set<String> keys() {
            return values.keySet();
        }
    }

    /** 与 RouteDecision 的常量保持一致，避免测试反向依赖智能体模块。 */
    private static final class RouteDecisionTarget {
        private static final String AGENT = "agent";
        private static final String TEAM = "team";
    }
}
