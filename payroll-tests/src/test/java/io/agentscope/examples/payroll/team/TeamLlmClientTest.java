package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
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

    private static HarnessAgent stubAgent() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.empty());
        return agent;
    }
}
