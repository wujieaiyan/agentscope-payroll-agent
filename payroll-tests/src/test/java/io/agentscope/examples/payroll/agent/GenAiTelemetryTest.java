package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageRecorder;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.agentscope.examples.payroll.usage.UsageModelCatalog;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** GenAI span（S5）：属性名对齐 semconv 1.41，成功/失败都只结束一次。 */
class GenAiTelemetryTest {

    private final UsageRecordingMiddleware middleware = new UsageRecordingMiddleware();
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        LlmUsageRecorder.configure(new SilentStore(), false);
        UsageModelCatalog.clear();
    }

    @AfterEach
    void tearDown() {
        LlmUsageRecorder.reset();
        UsageModelCatalog.clear();
        GlobalOpenTelemetry.resetForTest();
        tracerProvider.close();
    }

    @Test
    @DisplayName("成功调用产出一个 chat span，带模型、归因与 token 属性")
    void recordsChatSpanWithUsage() {
        Model model = model("qwen2.5:3b");
        UsageModelCatalog.register(model, "控制面", "ollama");

        middleware
                .onModelCall(
                        agent(),
                        context(),
                        new ModelCallInput(List.of(), List.of(), null, model),
                        ignored ->
                                Flux.just(
                                        new ModelCallEndEvent(
                                                "reply-1", new ChatUsage(100, 20, 30, 1.5))))
                .collectList()
                .block();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("chat qwen2.5:3b", span.getName());
        // 成功 span 保持 UNSET（semconv 约定），只有失败才显式标 ERROR。
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        assertEquals(
                "chat",
                span.getAttributes().get(GenAiTelemetry.OPERATION_NAME));
        assertEquals(
                "ollama",
                span.getAttributes().get(GenAiTelemetry.PROVIDER_NAME));
        assertEquals(
                "控制面",
                span.getAttributes().get(GenAiTelemetry.PLANE));
        assertEquals(
                "qwen2.5:3b",
                span.getAttributes().get(GenAiTelemetry.REQUEST_MODEL));
        assertEquals("control", span.getAttributes().get(GenAiTelemetry.AGENT_ID));
        assertEquals("薪酬助手", span.getAttributes().get(GenAiTelemetry.AGENT_NAME));
        assertEquals("42", span.getAttributes().get(GenAiTelemetry.CONVERSATION_ID));
        assertEquals(100L, span.getAttributes().get(GenAiTelemetry.INPUT_TOKENS));
        assertEquals(20L, span.getAttributes().get(GenAiTelemetry.OUTPUT_TOKENS));
    }

    @Test
    @DisplayName("失败调用只结束一个 span 并标记 ERROR")
    void failedCallMarksErrorOnce() {
        Model model = model("qwen2.5:3b");

        middleware
                .onModelCall(
                        agent(),
                        context(),
                        new ModelCallInput(List.of(), List.of(), null, model),
                        ignored -> Flux.error(new IllegalArgumentException("boom")))
                .onErrorResume(error -> Flux.empty())
                .collectList()
                .block();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertTrue(
                spans.get(0).getEvents().stream()
                        .anyMatch(event -> event.getName().equals("exception")));
    }

    private static Agent agent() {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn("control");
        when(agent.getName()).thenReturn("薪酬助手");
        return agent;
    }

    private static Model model(String name) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn(name);
        return model;
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("7").sessionId("42").build();
    }

    private static final class SilentStore implements LlmUsageStore {

        private final List<LlmUsageRecord> rows = new ArrayList<>();

        @Override
        public long insert(LlmUsageRecord record) {
            rows.add(record);
            return rows.size();
        }

        @Override
        public List<LlmUsageRecord> find(
                io.agentscope.examples.payroll.usage.LlmUsageQuery query, int limit) {
            return List.copyOf(rows);
        }
    }
}
