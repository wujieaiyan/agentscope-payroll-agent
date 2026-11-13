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
        ChatRouting routing,
        List<ActionProposal> proposals,
        LocalDateTime createdAt) {

    public ChatMessage {
        tools = tools == null ? List.of() : List.copyOf(tools);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    /** 升级前或普通消息：没有路由快照与提案。 */
    public ChatMessage(
            long id,
            long sessionId,
            String role,
            String content,
            List<String> tools,
            String model,
            LocalDateTime createdAt) {
        this(id, sessionId, role, content, tools, model, null, List.of(), createdAt);
    }
}
