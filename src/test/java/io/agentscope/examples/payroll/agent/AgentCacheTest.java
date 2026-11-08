package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentCacheTest {

    @Test
    @DisplayName("相同 key 只构建一次并复用实例")
    void reusesInstanceForSameKey() {
        AgentCache cache = new AgentCache();
        AtomicInteger builds = new AtomicInteger();

        Object first = cache.get("team|a", builds::incrementAndGet);
        Object second = cache.get("team|a", builds::incrementAndGet);

        assertSame(first, second);
        assertEquals(1, builds.get());
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("不同 key 各自构建")
    void buildsSeparatelyForDifferentKeys() {
        AgentCache cache = new AgentCache();
        Object first = cache.get("team|a", Object::new);
        Object second = cache.get("team|b", Object::new);

        assertNotSame(first, second);
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("并发首次访问同一 key 也只构建一次")
    void buildsOnceUnderConcurrency() throws Exception {
        AgentCache cache = new AgentCache();
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(
                        () -> {
                            start.await();
                            return cache.get(
                                    "team|a",
                                    () -> {
                                        builds.incrementAndGet();
                                        return new Object();
                                    });
                        });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, builds.get());
    }

    @Test
    @DisplayName("clear 关闭 AutoCloseable 实例并允许重新构建")
    void clearClosesEntries() {
        AgentCache cache = new AgentCache();
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable closeable =
                () -> {
                    closed.incrementAndGet();
                };

        cache.get("team|a", () -> closeable);
        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(1, closed.get());
        assertNotSame(closeable, cache.get("team|a", Object::new));
    }

    @Test
    @DisplayName("定义签名随名称、描述或技能变化")
    void definitionSignatureCoversMutableFields() {
        AgentDefinition base = definition("a1", "薪酬计算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition same = definition("a1", "薪酬计算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition renamed = definition("a1", "薪酬核算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition rechained =
                definition("a1", "薪酬计算", "计算工资", List.of("payroll-validator"));

        assertEquals(PayrollAgentFactory.definitionSignature(base),
                PayrollAgentFactory.definitionSignature(same));
        assertNotSameSignature(base, renamed);
        assertNotSameSignature(base, rechained);
    }

    private static void assertNotSameSignature(AgentDefinition left, AgentDefinition right) {
        String leftSignature = PayrollAgentFactory.definitionSignature(left);
        String rightSignature = PayrollAgentFactory.definitionSignature(right);
        assertTrue(
                !leftSignature.equals(rightSignature),
                "定义签名应随字段变化：" + leftSignature + " vs " + rightSignature);
    }

    private static AgentDefinition definition(
            String id, String name, String description, List<String> skillIds) {
        return new AgentDefinition(
                id,
                name,
                "avatar",
                description,
                List.of(),
                skillIds,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                LocalDateTime.now(),
                LocalDateTime.now());
    }
}
