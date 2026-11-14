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
        LocalDateTime createdAt,
        List<Citation> citations) {

    public ChatMessage {
        tools = tools == null ? List.of() : List.copyOf(tools);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 升级前或普通消息：没有路由快照、提案与引用。 */
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

    /** 未携带引用的消息：保持原有构造方式兼容。 */
    public ChatMessage(
            long id,
            long sessionId,
            String role,
            String content,
            List<String> tools,
            String model,
            ChatRouting routing,
            List<ActionProposal> proposals,
            LocalDateTime createdAt) {
        this(id, sessionId, role, content, tools, model, routing, proposals, createdAt, List.of());
    }
}
