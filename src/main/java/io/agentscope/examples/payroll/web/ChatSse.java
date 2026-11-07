package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 把对话事件映射为 SSE 事件，供单会话与多会话接口共用。 */
final class ChatSse {

    private static final Logger log = LoggerFactory.getLogger(ChatSse.class);

    private ChatSse() {
    }

    static ChatStreamListener bridge(Channel channel, Consumer<PayrollChat.Reply> onDone) {
        return new ChatStreamListener() {
            @Override
            public void onStart(String model) {
                channel.send("start", payload("model", model));
            }

            @Override
            public void onAgentStart(String agent, String label) {
                channel.send("agent_start", payload("agent", agent, "label", label));
            }

            @Override
            public void onAgentOutput(String agent, String delta) {
                channel.send("agent_text", payload("agent", agent, "text", delta));
            }

            @Override
            public void onAgentEnd(String agent) {
                channel.send("agent_end", payload("agent", agent));
            }

            @Override
            public void onThinking(String delta) {
                channel.send("thinking", payload("text", delta));
            }

            @Override
            public void onPhase(String label) {
                channel.send("phase", payload("label", label));
            }

            @Override
            public void onContextCompressed(int compressed, int kept, int summarizedTotal) {
                channel.send(
                        "context",
                        payload(
                                "compressed", compressed,
                                "kept", kept,
                                "summarized", summarizedTotal));
            }

            @Override
            public void onToolStart(String toolCallId, String toolName) {
                channel.send(
                        "tool_start", payload("tool_call_id", toolCallId, "name", toolName));
            }

            @Override
            public void onToolArgs(String toolCallId, String delta) {
                channel.send("tool_args", payload("tool_call_id", toolCallId, "text", delta));
            }

            @Override
            public void onToolResult(String toolCallId, String toolName, String state) {
                channel.send(
                        "tool_result",
                        payload(
                                "tool_call_id", toolCallId,
                                "name", toolName,
                                "state", state));
            }

            @Override
            public void onText(String delta) {
                channel.send("text", payload("text", delta));
            }

            @Override
            public void onDone(PayrollChat.Reply reply) {
                onDone.accept(reply);
                channel.send(
                        "done",
                        payload(
                                "reply", reply.reply(),
                                "tools", reply.tools(),
                                "model", reply.model()));
            }
        };
    }

    static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                payload.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return payload;
    }

    /** 客户端断开后停止写入，避免异常打断 Agent 事件流。 */
    static final class Channel {

        private final SseEmitter emitter;
        private boolean closed;

        Channel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(String event, Map<String, Object> data) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(JsonSupport.toJson(data)));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                log.debug("SSE 连接已关闭，停止推送 {} 事件", event);
            }
        }
    }
}
