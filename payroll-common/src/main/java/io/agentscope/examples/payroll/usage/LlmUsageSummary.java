package io.agentscope.examples.payroll.usage;

/** 时间窗内的总量指标，供看板顶部 KPI 卡片使用。 */
public record LlmUsageSummary(
        long calls,
        long successes,
        long failures,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long totalTokens,
        long avgLatencyMs,
        long activeUsers,
        long activeAgents) {
}
