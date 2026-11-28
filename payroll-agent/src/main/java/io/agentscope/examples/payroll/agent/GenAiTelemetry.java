package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.examples.payroll.usage.UsageModelCatalog;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTel GenAI span（S5）：一次模型调用一个 CLIENT span，属性名取自 semconv 1.41 的
 * {@code gen_ai.*}。Micrometer 指标继续记录聚合延迟，span 负责把调用挂到调用链上。
 *
 * <p>只依赖 OTel API；部署侧未安装 SDK 时 {@link GlobalOpenTelemetry} 返回 no-op，
 * 这里的所有调用都是空操作。用量事实仍以 {@code llm_usage} 落库为准，span 不替代计费数据。
 */
final class GenAiTelemetry {

    static final String INSTRUMENTATION = "io.agentscope.payroll.agent";

    static final AttributeKey<String> OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> PROVIDER_NAME =
            AttributeKey.stringKey("gen_ai.provider.name");
    static final AttributeKey<String> REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<Long> INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    static final AttributeKey<Long> OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    static final AttributeKey<String> AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
    static final AttributeKey<String> AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    static final AttributeKey<String> CONVERSATION_ID =
            AttributeKey.stringKey("gen_ai.conversation.id");
    static final AttributeKey<String> PLANE = AttributeKey.stringKey("payroll.llm.plane");

    private GenAiTelemetry() {}

    static Span startModelCall(Agent agent, RuntimeContext context, Model model) {
        String modelName = model == null ? null : model.getModelName();
        UsageModelCatalog.Meta meta = UsageModelCatalog.describe(model);
        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION);
        Span span =
                tracer.spanBuilder("chat " + (modelName == null || modelName.isBlank() ? "unknown" : modelName))
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(OPERATION_NAME, "chat")
                        .setAttribute(PROVIDER_NAME, meta.provider())
                        .setAttribute(PLANE, meta.plane())
                        .startSpan();
        if (modelName != null && !modelName.isBlank()) {
            span.setAttribute(REQUEST_MODEL, modelName);
        }
        if (agent != null) {
            if (agent.getAgentId() != null) {
                span.setAttribute(AGENT_ID, agent.getAgentId());
            }
            if (agent.getName() != null) {
                span.setAttribute(AGENT_NAME, agent.getName());
            }
        }
        if (context != null && context.getSessionId() != null) {
            span.setAttribute(CONVERSATION_ID, context.getSessionId());
        }
        return span;
    }

    /** 结束 span；同一个 span 只结束一次，错误同时写入 status 与 exception 事件。 */
    static void endModelCall(
            Span span, AtomicBoolean ended, ChatUsage usage, Throwable error) {
        if (span == null || !ended.compareAndSet(false, true)) {
            return;
        }
        try {
            if (usage != null) {
                span.setAttribute(INPUT_TOKENS, (long) Math.max(0, usage.getInputTokens()));
                span.setAttribute(OUTPUT_TOKENS, (long) Math.max(0, usage.getOutputTokens()));
            }
            if (error != null) {
                span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                span.recordException(error);
            }
        } finally {
            span.end();
        }
    }
}
