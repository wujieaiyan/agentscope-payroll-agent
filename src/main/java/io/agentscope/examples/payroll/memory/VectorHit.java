package io.agentscope.examples.payroll.memory;

import java.time.LocalDate;

/** 召回结果，score 为余弦相似度。 */
public record VectorHit(
        long sessionId,
        MemorySource source,
        long sourceId,
        String content,
        double score,
        LocalDate createdAt) {
}
