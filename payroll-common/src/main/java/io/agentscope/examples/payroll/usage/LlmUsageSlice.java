package io.agentscope.examples.payroll.usage;

/** 维度分组结果：key 用于筛选取值，label 用于展示。 */
public record LlmUsageSlice(
        String key,
        String label,
        long calls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long failures) {
}
