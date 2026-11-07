package io.agentscope.examples.payroll.memory;

import java.time.Instant;

/** 待写入向量库的一条记忆，content 已脱敏。 */
public record VectorRecord(
        long userId,
        long sessionId,
        MemorySource source,
        Long sourceId,
        int turnIndex,
        String content,
        float[] vector,
        Instant createdAt) {
}
