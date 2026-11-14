package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 把对话事件映射为 SSE 事件，供单会话与多会话接口共用。 */
public final class ChatSse {

    private static final Logger log = LoggerFactory.getLogger(ChatSse.class);

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

    public static ChatStreamListener bridge(
            Channel channel,
            ChatSessionManager manager,
            ChatSessionManager.TurnContext turn,
            Function<PayrollChat.Reply, Long> onDone) {
        return new ChatStreamListener() {

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
                if (cancelled()) {
                    return;
                }
                emit("agent_text", payload("agent", agent, "text", delta));
            }

            @Override
            public void onAgentEnd(String agent) {
                emit("agent_end", payload("agent", agent));
            }

            @Override
            public void onThinking(String delta) {
                if (cancelled()) {
                    return;
                }
                emit("thinking", payload("text", delta));
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
                if (cancelled()) {
                    return;
                }
                emit("tool_args", payload("tool_call_id", toolCallId, "text", delta));
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
                if (cancelled()) {
                    return;
                }
                emit("text", payload("text", delta));
            }

            @Override
            public void onActionProposal(ActionProposal proposal) {
                emit("action_proposal", payload("proposal", proposal));
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
                publish(channel, manager, turn, event, data);
            }
        };
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
        channel.send(event.event(), data, event.seq());
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
        private boolean closed;

        Channel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(String event, Map<String, Object> data) {
            send(event, data, 0L);
        }

        void send(String event, Map<String, Object> data, long id) {
            if (closed || emitter == null) {
                return;
            }
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
                if (id > 0) {
                    builder.id(Long.toString(id));
                }
                emitter.send(builder.data(JsonSupport.toJson(data)));
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
