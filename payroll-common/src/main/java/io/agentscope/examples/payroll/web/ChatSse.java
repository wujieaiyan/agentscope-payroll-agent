package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 把对话事件映射为 SSE 事件，供单会话与多会话接口共用。 */
public final class ChatSse {

    private static final Logger log = LoggerFactory.getLogger(ChatSse.class);

    /** 文本类增量合并窗口：同一窗口内的 token 合并成一条事件，降低总线与 SSE 写入次数。 */
    private static final long DELTA_FLUSH_MILLIS = 80L;

    private static final ScheduledExecutorService DELTA_FLUSH_POOL =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "sse-delta-flush");
                        thread.setDaemon(true);
                        return thread;
                    });

    private ChatSse() {
    }

    public static ChatStreamListener bridge(Channel channel, Function<PayrollChat.Reply, Long> onDone) {
        return bridge(channel, null, null, onDone);
    }

    /** runtime 节点专用：事件只写总线，由 Web 节点尾随转发。 */
    public static ChatStreamListener busOnly(
            ChatSessionManager manager,
            ChatSessionManager.TurnContext turn,
            Function<PayrollChat.Reply, Long> onDone) {
        return bridge(new Channel(null), manager, turn, onDone);
    }

    /**
     * 创建通道并接管 SSE 生命周期。
     *
     * <p>容器报错时响应已不可用，只关闭通道、不再 complete；超时才主动结束。
     */
    public static Channel attach(SseEmitter emitter) {
        Channel channel = new Channel(emitter);
        emitter.onTimeout(channel::complete);
        emitter.onError(error -> channel.close());
        emitter.onCompletion(channel::close);
        return channel;
    }

    public static ChatStreamListener bridge(
            Channel channel,
            ChatSessionManager manager,
            ChatSessionManager.TurnContext turn,
            Function<PayrollChat.Reply, Long> onDone) {
        return new ChatStreamListener() {

            private final DeltaCoalescer deltas =
                    new DeltaCoalescer(
                            (event, data) -> publish(channel, manager, turn, event, data),
                            this::cancelled);
            private long cancelCheckedAt;
            private boolean cancelled;

            @Override
            public void onStart(String model) {
                emit("start", payload("model", model));
            }

            @Override
            public void onAgentStart(String agent, String label) {
                emit("agent_start", payload("agent", agent, "label", label));
            }

            @Override
            public void onAgentOutput(String agent, String delta) {
                deltas.add("agent:" + agent, "agent_text", payload("agent", agent), delta);
            }

            @Override
            public void onAgentEnd(String agent) {
                emit("agent_end", payload("agent", agent));
            }

            @Override
            public void onThinking(String delta) {
                deltas.add("thinking", "thinking", null, delta);
            }

            @Override
            public void onPhase(String label) {
                emit("phase", payload("label", label));
            }

            @Override
            public void onContextCompressed(int compressed, int kept, int summarizedTotal) {
                emit(
                        "context",
                        payload(
                                "compressed", compressed,
                                "kept", kept,
                                "summarized", summarizedTotal));
            }

            @Override
            public void onToolStart(String toolCallId, String toolName) {
                emit("tool_start", payload("tool_call_id", toolCallId, "name", toolName));
            }

            @Override
            public void onToolArgs(String toolCallId, String delta) {
                deltas.add(
                        "tool:" + toolCallId,
                        "tool_args",
                        payload("tool_call_id", toolCallId),
                        delta);
            }

            @Override
            public void onToolResult(String toolCallId, String toolName, String state) {
                emit(
                        "tool_result",
                        payload(
                                "tool_call_id", toolCallId,
                                "name", toolName,
                                "state", state));
            }

            @Override
            public void onToolResult(
                    String toolCallId, String toolName, String state, ChatToolTrace trace) {
                emit(
                        "tool_result",
                        payload(
                                "tool_call_id", toolCallId,
                                "name", toolName,
                                "state", state,
                                "duration_ms", trace.durationMs(),
                                "amounts", trace.amounts().isEmpty() ? null : trace.amounts(),
                                "employees",
                                        trace.employees().isEmpty() ? null : trace.employees(),
                                "period", trace.period(),
                                "department", trace.department(),
                                "truncated", trace.truncated() ? Boolean.TRUE : null));
            }

            @Override
            public void onText(String delta) {
                deltas.add("text", "text", null, delta);
            }

            @Override
            public void onActionProposal(ActionProposal proposal) {
                emit("action_proposal", payload("proposal", proposal));
            }

            @Override
            public void onCitations(List<Citation> citations) {
                if (citations != null && !citations.isEmpty()) {
                    emit("citation", payload("citations", citations));
                }
            }

            @Override
            public void onDone(PayrollChat.Reply reply) {
                boolean wasCancelled =
                        manager != null && turn != null && manager.turnCancelled(turn);
                Long messageId = onDone.apply(reply);
                if (wasCancelled) {
                    // 取消发生在模型返回之前时，回答不落库，直接以取消事件结束。
                    emit("error", payload("message", "本轮回答已取消", "cancelled", true));
                    return;
                }
                emit(
                        "done",
                        payload(
                                "reply", reply.reply(),
                                "tools", reply.tools(),
                                "model", reply.model(),
                                "message_id", messageId,
                                "trace_count",
                                        reply.traces().isEmpty()
                                                ? null
                                                : reply.traces().size(),
                                "routing", reply.routing(),
                                "proposals",
                                        reply.proposals().isEmpty()
                                                ? null
                                                : reply.proposals(),
                                "citations",
                                        reply.citations().isEmpty()
                                                ? null
                                                : reply.citations(),
                                "cache", reply.cache()));
            }

            private boolean cancelled() {
                if (manager == null || turn == null) {
                    return false;
                }
                long now = System.currentTimeMillis();
                if (now - cancelCheckedAt >= 500) {
                    cancelCheckedAt = now;
                    cancelled = manager.turnCancelled(turn);
                }
                return cancelled;
            }

            private void emit(String event, Map<String, Object> data) {
                deltas.emit(event, data);
            }
        };
    }

    /**
     * 按 key 合并短窗口内的增量事件，并在非增量事件之前强制冲刷，保证事件顺序。
     *
     * <p>text / thinking / agent_text / tool_args 都是逐 token 产生，逐条写入会放大
     * Redis 与 SSE 的写次数；合并到 80ms 粒度后对前端仍近似实时。
     */
    private static final class DeltaCoalescer {

        private final BiConsumer<String, Map<String, Object>> sink;
        private final BooleanSupplier cancelled;
        private final Map<String, Delta> pending = new LinkedHashMap<>();
        private final Object lock = new Object();
        private boolean scheduled;

        DeltaCoalescer(
                BiConsumer<String, Map<String, Object>> sink, BooleanSupplier cancelled) {
            this.sink = sink;
            this.cancelled = cancelled;
        }

        void add(String key, String event, Map<String, Object> base, String text) {
            if (text == null || text.isEmpty() || cancelled.getAsBoolean()) {
                return;
            }
            synchronized (lock) {
                pending.computeIfAbsent(key, ignored -> new Delta(event, base))
                        .text
                        .append(text);
                if (!scheduled) {
                    scheduled = true;
                    DELTA_FLUSH_POOL.schedule(
                            this::flush, DELTA_FLUSH_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        }

        void emit(String event, Map<String, Object> data) {
            synchronized (lock) {
                flushLocked();
                sink.accept(event, data);
            }
        }

        private void flush() {
            synchronized (lock) {
                scheduled = false;
                flushLocked();
            }
        }

        private void flushLocked() {
            if (pending.isEmpty()) {
                return;
            }
            List<Delta> batch = new ArrayList<>(pending.values());
            pending.clear();
            if (cancelled.getAsBoolean()) {
                return;
            }
            for (Delta delta : batch) {
                Map<String, Object> data = new LinkedHashMap<>(delta.base);
                data.put("text", delta.text.toString());
                sink.accept(delta.event, data);
            }
        }

        private static final class Delta {

            private final String event;
            private final Map<String, Object> base;
            private final StringBuilder text = new StringBuilder();

            private Delta(String event, Map<String, Object> base) {
                this.event = event;
                this.base = base == null ? new LinkedHashMap<>() : base;
            }
        }
    }

    private static void publish(
            Channel channel,
            ChatSessionManager manager,
            ChatSessionManager.TurnContext turn,
            String event,
            Map<String, Object> data) {
        if (manager == null || turn == null) {
            channel.send(event, data);
            return;
        }
        long seq = manager.emit(turn, event, data);
        if (seq <= 0) {
            channel.send(event, data);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("seq", seq);
        channel.send(event, payload, seq);
    }

    /** 续流时重放事件：补齐序号，前端据此去重并记录断点。 */
    public static void replay(Channel channel, TurnEventBus.TurnEvent event) {
        Map<String, Object> data;
        try {
            data =
                    JsonSupport.fromJson(
                            event.data(),
                            new TypeReference<LinkedHashMap<String, Object>>() {
                            });
        } catch (RuntimeException e) {
            data = new LinkedHashMap<>();
        }
        data.put("seq", event.seq());
        data.put("v", event.version());
        if (event.timestamp() > 0) {
            data.put("ts", event.timestamp());
        }
        channel.send(event.event(), data, event.seq());
    }

    /** 重放合并后的文本块：id 取最后一条事件的序号，前端游标继续有效。 */
    public static void replayText(Channel channel, long seq, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        channel.send("text", payload("text", text, "seq", seq), seq);
    }

    /** 空闲心跳：不带 SSE id，避免污染 Last-Event-ID 游标。 */
    public static void heartbeat(Channel channel, long afterSeq) {
        channel.send(
                "ping",
                payload(
                        "after_seq", Math.max(0, afterSeq),
                        "ts", System.currentTimeMillis()));
    }


    public static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                payload.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return payload;
    }

    /** 客户端断开后停止写入，避免异常打断 Agent 事件流。 */
    public static final class Channel {

        private final SseEmitter emitter;
        private volatile boolean closed;

        Channel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void close() {
            closed = true;
        }

        /**
         * 结束 SSE 响应：已断开或已完成时不再 complete。
         *
         * <p>容器报错后响应已不可用，此时再 complete 会 flush 失败并抛出
         * 「Response not usable after response errors」。
         */
        public void complete() {
            if (emitter == null) {
                closed = true;
                return;
            }
            if (closed) {
                return;
            }
            closed = true;
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("SSE 完成时连接已不可用，忽略：{}", e.getMessage());
            }
        }

        void send(String event, Map<String, Object> data) {
            send(event, data, 0L);
        }

        void send(String event, Map<String, Object> data, long id) {
            if (closed || emitter == null) {
                return;
            }
            // 信封字段统一补齐：旧前端忽略新增字段，新前端可据 v/ts 做兼容与耗时统计。
            Map<String, Object> payload =
                    data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
            payload.putIfAbsent("v", TurnEventBus.VERSION);
            payload.putIfAbsent("ts", System.currentTimeMillis());
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
                if (id > 0) {
                    builder.id(Long.toString(id));
                }
                emitter.send(builder.data(JsonSupport.toJson(payload)));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                log.debug("SSE 连接已关闭，停止推送 {} 事件", event);
            }
        }

        boolean closed() {
            return closed;
        }
    }
}
