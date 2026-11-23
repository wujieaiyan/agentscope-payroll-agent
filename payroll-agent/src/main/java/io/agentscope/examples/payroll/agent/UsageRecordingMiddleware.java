package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageRecorder;
import io.agentscope.examples.payroll.usage.UsageModelCatalog;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 模型调用埋点：每次底层模型调用落一行 token 用量事实，供用量看板聚合。
 *
 * <p>挂在 {@link MiddlewareBase#onModelCall} 上而非包装 {@link Model}，因为只有中间件
 * 能同时拿到 {@link RuntimeContext}（用户/会话）与 {@link Agent}（智能体归因）；
 * 失败行记录异常类型，成功行以 {@link ModelCallEndEvent} 的实际 usage 为准。
 */
final class UsageRecordingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingMiddleware.class);
    private static final int MAX_ERROR_TYPE = 64;

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        AtomicLong startNanos = new AtomicLong(-1);
        AtomicBoolean recorded = new AtomicBoolean(false);
        Flux<AgentEvent> stream;
        try {
            stream = next.apply(input);
        } catch (RuntimeException e) {
            record(new Call(agent, ctx, input), startNanos, null, e);
            throw e;
        }
        return stream.doOnSubscribe(s -> startNanos.compareAndSet(-1, System.nanoTime()))
                .doOnNext(
                        event -> {
                            if (event instanceof ModelCallEndEvent end
                                    && recorded.compareAndSet(false, true)) {
                                record(new Call(agent, ctx, input), startNanos, end, null);
                            }
                        })
                .doOnError(
                        error -> {
                            if (recorded.compareAndSet(false, true)) {
                                record(new Call(agent, ctx, input), startNanos, null, error);
                            }
                        });
    }

    private void record(Call call, AtomicLong startNanos, ModelCallEndEvent end, Throwable error) {
        try {
            long start = startNanos.get();
            long latencyMs = start < 0 ? 0 : Math.max(0, (System.nanoTime() - start) / 1_000_000);
            Model model = call.input.model();
            String modelName = model == null ? null : model.getModelName();
            UsageModelCatalog.Meta meta = UsageModelCatalog.describe(model);
            TraceScope scope = call.ctx == null ? null : call.ctx.get(TraceScope.class);
            ChatUsage usage = end == null ? null : end.getUsage();
            int prompt = usage == null ? 0 : Math.max(0, usage.getInputTokens());
            int completion = usage == null ? 0 : Math.max(0, usage.getOutputTokens());
            int cached = usage == null ? 0 : Math.max(0, usage.getCachedTokens());
            LlmUsageRecord record =
                    new LlmUsageRecord(
                            0L,
                            LocalDateTime.now(),
                            call.ctx == null ? null : call.ctx.getUserId(),
                            call.ctx == null ? null : call.ctx.getSessionId(),
                            call.agent == null ? null : call.agent.getAgentId(),
                            call.agent == null ? null : call.agent.getName(),
                            scope == null ? null : scope.role(),
                            scope == null ? null : scope.stageKey(),
                            scope == null || scope.runId() <= 0 ? null : scope.runId(),
                            modelName,
                            meta.provider(),
                            meta.plane(),
                            prompt,
                            completion,
                            cached,
                            usage == null ? 0 : prompt + completion,
                            latencyMs,
                            error == null,
                            error == null ? null : errorType(error),
                            end == null ? null : end.getReplyId());
            LlmUsageRecorder.record(record);
        } catch (RuntimeException e) {
            log.warn("[用量看板] 记录模型用量失败：原因={}", e.toString());
        }
    }

    private static String errorType(Throwable error) {
        String type = error.getClass().getSimpleName();
        if (type == null || type.isBlank()) {
            type = error.getClass().getName();
        }
        return type.length() <= MAX_ERROR_TYPE ? type : type.substring(0, MAX_ERROR_TYPE);
    }

    private record Call(Agent agent, RuntimeContext ctx, ModelCallInput input) {
    }
}
