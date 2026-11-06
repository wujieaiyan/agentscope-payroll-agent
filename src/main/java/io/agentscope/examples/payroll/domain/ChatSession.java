package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 某个用户的一个对话窗口；消息与会话均按用户隔离。 */
public record ChatSession(
        long id,
        long userId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int messageCount,
        String lastMessage) {
}
