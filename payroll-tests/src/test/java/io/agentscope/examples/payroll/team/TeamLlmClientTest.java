package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TeamLlmClientTest {

    @Test
    @DisplayName("构造 Bean 时不解析模型，首次调用才按连接版本构建")
    void buildsLazilyOnFirstCall() {
        AtomicInteger builds = new AtomicInteger();
        TeamLlmClient client =
                new TeamLlmClient(
                        () -> "control|v1",
                        () -> {
                            builds.incrementAndGet();
                            return stubAgent();
                        });

        assertTrue(builds.get() == 0);

        client.completeJson("instruction", "question", "session");

        assertTrue(builds.get() == 1);
    }

    @Test
    @DisplayName("轻量面连接版本变化后重建计划器，并改用新实例")
    void rebuildsWhenUtilityVersionChanges() {
        AtomicReference<String> version = new AtomicReference<>("control|v1");
        AtomicInteger builds = new AtomicInteger();
        HarnessAgent first = stubAgent();
        HarnessAgent second = stubAgent();
        TeamLlmClient client =
                new TeamLlmClient(
                        version::get, () -> builds.incrementAndGet() == 1 ? first : second);

        assertNull(client.completeJson("instruction", "question", "session"));
        verify(first, times(1)).call(anyList(), any(RuntimeContext.class));

        version.set("control|v2");
        assertNull(client.completeJson("instruction", "question", "session"));

        assertTrue(builds.get() == 2);
        verify(second, times(1)).call(anyList(), any(RuntimeContext.class));
        verify(first, times(1)).call(anyList(), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("连接版本未变时复用原计划器")
    void reusesAgentWhenVersionUnchanged() {
        AtomicInteger builds = new AtomicInteger();
        HarnessAgent agent = stubAgent();
        TeamLlmClient client = new TeamLlmClient(() -> "control|v1", () -> {
            builds.incrementAndGet();
            return agent;
        });

        client.completeJson("instruction", "question", "session");
        client.completeJson("instruction", "question", "session");

        assertTrue(builds.get() == 1);
        verify(agent, times(2)).call(anyList(), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("重建失败时沿用旧计划器，不向调用方抛出")
    void keepsOldAgentWhenRebuildFails() {
        AtomicReference<String> version = new AtomicReference<>("control|v1");
        AtomicInteger builds = new AtomicInteger();
        HarnessAgent first = stubAgent();
        TeamLlmClient client =
                new TeamLlmClient(
                        version::get,
                        () -> {
                            if (builds.incrementAndGet() > 1) {
                                throw new IllegalStateException("连接不可用");
                            }
                            return first;
                        });

        client.completeJson("instruction", "question", "session");
        version.set("control|v2");
        assertNull(client.completeJson("instruction", "question", "session"));

        assertTrue(builds.get() == 2);
        verify(first, times(2)).call(anyList(), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("读取版本失败时沿用旧计划器")
    void keepsOldAgentWhenVersionReadFails() {
        AtomicInteger reads = new AtomicInteger();
        HarnessAgent agent = stubAgent();
        TeamLlmClient client =
                new TeamLlmClient(
                        () -> {
                            if (reads.incrementAndGet() > 1) {
                                throw new IllegalStateException("注册表不可用");
                            }
                            return "control|v1";
                        },
                        () -> agent);

        client.completeJson("instruction", "question", "session");
        assertNull(client.completeJson("instruction", "question", "session"));

        verify(agent, times(2)).call(anyList(), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("并发调用不再全局串行：许可未被占满时第二个调用立即进入模型")
    void doesNotSerializeCallsUnderPermitLimit() throws Exception {
        AtomicInteger entered = new AtomicInteger();
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.<Msg>never().doOnSubscribe(s -> entered.incrementAndGet()));
        TeamLlmClient client = new TeamLlmClient(agent, 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first =
                    pool.submit(
                            () -> client.completeJson("i", "q", "s1", Duration.ofMillis(1500)));
            long deadline = System.currentTimeMillis() + 1000;
            while (entered.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, entered.get(), "首个调用应已进入模型");

            long secondStartedAt = System.currentTimeMillis();
            Future<String> second =
                    pool.submit(
                            () -> client.completeJson("i", "q", "s2", Duration.ofMillis(1500)));
            while (entered.get() < 2 && System.currentTimeMillis() < deadline + 800) {
                Thread.sleep(10);
            }
            assertEquals(2, entered.get(), "第二个调用不应等待第一个调用结束");
            assertTrue(System.currentTimeMillis() - secondStartedAt < 800);

            assertNull(first.get());
            assertNull(second.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("许可耗尽时最多等待 2 秒，随后返回 null 交给规则兜底")
    void fallsBackWhenPermitsExhausted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.<Msg>never().doOnSubscribe(s -> entered.countDown()));
        TeamLlmClient client = new TeamLlmClient(agent, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> blocking =
                    pool.submit(
                            () -> client.completeJson("i", "q", "hold", Duration.ofMillis(3000)));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "阻塞调用应先取得唯一许可");

            long startedAt = System.currentTimeMillis();
            assertNull(client.completeJson("i", "q", "fallback", Duration.ofMillis(500)));
            long elapsed = System.currentTimeMillis() - startedAt;
            assertTrue(elapsed >= 1800 && elapsed < 3500, "许可等待时长=" + elapsed + "ms");

            assertNull(blocking.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static HarnessAgent stubAgent() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.empty());
        return agent;
    }
}
