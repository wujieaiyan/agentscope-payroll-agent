package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageRecorder;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.agentscope.examples.payroll.usage.UsageModelCatalog;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class UsageRecordingMiddlewareTest {

    private CapturingStore store;
    private final UsageRecordingMiddleware middleware = new UsageRecordingMiddleware();

    @BeforeEach
    void setUp() {
        store = new CapturingStore();
        LlmUsageRecorder.configure(store, false);
        UsageModelCatalog.clear();
    }

    @AfterEach
    void tearDown() {
        LlmUsageRecorder.reset();
        UsageModelCatalog.clear();
    }

    @Test
    @DisplayName("成功调用记录 usage、归因与使用面")
    void recordsSuccessfulCall() {
        Agent agent = agent("control", "薪酬助手");
        Model model = model("qwen2.5:3b");
        UsageModelCatalog.register(model, "控制面", "ollama");
        RuntimeContext ctx = context();

        List<AgentEvent> events =
                middleware
                        .onModelCall(agent, ctx, input(model), ignored ->
                                Flux.just(new ModelCallEndEvent("reply-1", new ChatUsage(100, 20, 30, 1.5))))
                        .collectList()
                        .block();

        assertEquals(1, events.size());
        assertEquals(1, store.rows.size());
        LlmUsageRecord row = store.rows.get(0);
        assertTrue(row.success());
        assertEquals("7", row.userId());
        assertEquals("42", row.sessionId());
        assertEquals("control", row.agentId());
        assertEquals("薪酬助手", row.agentName());
        assertEquals("implementer", row.role());
        assertEquals("dev.task", row.stageKey());
        assertEquals(9L, row.runId());
        assertEquals("qwen2.5:3b", row.model());
        assertEquals("ollama", row.provider());
        assertEquals("控制面", row.plane());
        assertEquals(100, row.promptTokens());
        assertEquals(20, row.completionTokens());
        assertEquals(30, row.cachedTokens());
        assertEquals(120, row.totalTokens());
        assertEquals("reply-1", row.replyId());
        assertNotNull(row.occurredAt());
    }

    @Test
    @DisplayName("失败调用记录异常类型，token 为零且不吞掉错误")
    void recordsFailedCall() {
        Agent agent = agent("control", "薪酬助手");
        Model model = model("qwen2.5:3b");
        RuntimeContext ctx = context();

        Flux<AgentEvent> stream =
                middleware.onModelCall(agent, ctx, input(model), ignored ->
                        Flux.error(new IllegalArgumentException("boom")));

        assertEquals(0, stream.onErrorResume(error -> Flux.empty()).collectList().block().size());
        assertEquals(1, store.rows.size());
        LlmUsageRecord row = store.rows.get(0);
        assertFalse(row.success());
        assertEquals("IllegalArgumentException", row.errorType());
        assertEquals(0, row.totalTokens());
        assertEquals("薪酬助手", row.agentName());
        assertEquals("未标注", row.plane());
    }

    @Test
    @DisplayName("一次调用只记录一行（结束事件与错误不会重复计数）")
    void recordsOncePerCall() {
        Agent agent = agent("router", "路由");
        Model model = model("qwen2.5:3b");
        UsageModelCatalog.register(model, "轻量面", "ollama");

        middleware
                .onModelCall(agent, context(), input(model), ignored ->
                        Flux.just(new ModelCallEndEvent("reply-2", new ChatUsage(10, 2, 0, 0.3))))
                .collectList()
                .block();

        assertEquals(1, store.rows.size());
        assertEquals("轻量面", store.rows.get(0).plane());
    }

    private static Agent agent(String id, String name) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    private static Model model(String name) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn(name);
        return model;
    }

    private static ModelCallInput input(Model model) {
        return new ModelCallInput(List.of(), List.of(), null, model);
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .userId("7")
                .sessionId("42")
                .put(
                        TraceScope.class,
                        new TraceScope(9L, "plan-1", "node-1", "control", "implementer", "ACTIVE", "dev.task"))
                .build();
    }

    private static final class CapturingStore implements LlmUsageStore {

        private final List<LlmUsageRecord> rows = new ArrayList<>();

        @Override
        public long insert(LlmUsageRecord record) {
            rows.add(record);
            return rows.size();
        }

        @Override
        public List<LlmUsageRecord> find(LlmUsageQuery query, int limit) {
            return List.copyOf(rows);
        }
    }
}
