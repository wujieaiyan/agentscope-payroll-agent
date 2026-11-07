package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 持久化的对话消息。role 为 USER/ASSISTANT/ERROR。 */
public record ChatMessage(
        long id,
        long sessionId,
        String role,
        String content,
        List<String> tools,
        String model,
        LocalDateTime createdAt) {

    public ChatMessage {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
