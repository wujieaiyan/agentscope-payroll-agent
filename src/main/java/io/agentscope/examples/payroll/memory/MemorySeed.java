package io.agentscope.examples.payroll.memory;

/** 重建向量索引时的原始记录，来自 H2 的会话消息与摘要。 */
public record MemorySeed(
        long userId,
        long sessionId,
        Long messageId,
        int turnIndex,
        MemorySource source,
        String content) {
}
