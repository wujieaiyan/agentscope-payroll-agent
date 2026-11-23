package io.agentscope.examples.payroll.agent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.examples.payroll.service.TurnMetrics;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 同平面连接级故障切换：仅当订阅后 5 秒内、首个响应元素之前发生连接类错误时，
 * 切换到优先级最高的备用连接一次；切换后失败不再重试，已产出内容后也不再切换。
 */
public final class FailoverModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FailoverModel.class);
    static final long FAILOVER_BUDGET_MILLIS = 5_000L;

    private final Model primary;
    private final String plane;
    private final String primaryId;
    private final Supplier<Model> fallbackSupplier;

    private volatile Model fallback;
    private volatile boolean fallbackResolved;

    public FailoverModel(
            Model primary, String plane, String primaryId, Supplier<Model> fallbackSupplier) {
        this.primary = primary;
        this.plane = plane;
        this.primaryId = primaryId;
        this.fallbackSupplier = fallbackSupplier;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(
                () -> {
                    long startedAt = System.currentTimeMillis();
                    AtomicBoolean emitted = new AtomicBoolean(false);
                    AtomicBoolean switched = new AtomicBoolean(false);
                    return primary.stream(messages, tools, options)
                            .doOnNext(response -> emitted.set(true))
                            .onErrorResume(
                                    error -> {
                                        if (emitted.get()
                                                || !isConnectionFailure(error)
                                                || System.currentTimeMillis() - startedAt
                                                        > FAILOVER_BUDGET_MILLIS) {
                                            return Flux.error(error);
                                        }
                                        if (!switched.compareAndSet(false, true)) {
                                            return Flux.error(error);
                                        }
                                        Model backup = fallback();
                                        if (backup == null) {
                                            return Flux.error(error);
                                        }
                                        log.warn(
                                                "[Model] {}模型连接 {} 首包失败（{}），切换到备用连接",
                                                plane,
                                                primaryId,
                                                error.getMessage());
                                        TurnMetrics.modelFailover(plane);
                                        return backup.stream(messages, tools, options);
                                    });
                });
    }

    private Model fallback() {
        if (fallbackResolved) {
            return fallback;
        }
        synchronized (this) {
            if (!fallbackResolved) {
                try {
                    fallback = fallbackSupplier == null ? null : fallbackSupplier.get();
                } catch (RuntimeException e) {
                    log.warn(
                            "[Model] {}备用模型构建失败，保留主连接错误：{}", plane, e.getMessage());
                    fallback = null;
                }
                fallbackResolved = true;
            }
        }
        return fallback;
    }

    private static boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return primary.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return primary.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return primary.getContextWindowSize();
    }
}
