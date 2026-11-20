package io.agentscope.examples.payroll.usage;

import java.time.LocalDateTime;

/**
 * 单次模型调用的 token 用量事实。
 *
 * <p>每次流式调用（一次 {@code stream()}）记一行，token 取模型返回的 usage；
 * 模型未返回 usage 时仍记录调用与时延，token 记为 0，避免调用次数被低估。
 */
public record LlmUsageRecord(
        long id,
        LocalDateTime occurredAt,
        String userId,
        String sessionId,
        String agentId,
        String agentName,
        String role,
        String stageKey,
        Long runId,
        String model,
        String provider,
        String plane,
        int promptTokens,
        int completionTokens,
        int cachedTokens,
        int totalTokens,
        long latencyMs,
        boolean success,
        String errorType,
        String replyId) {
}
