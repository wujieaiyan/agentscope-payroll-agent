package io.agentscope.examples.payroll.usage;

import java.time.LocalDateTime;

/** 趋势图时间桶：24 小时窗按小时，7/30 天窗按天，空缺桶补零。 */
public record LlmUsagePoint(
        LocalDateTime bucket,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long calls,
        long failures) {
}
