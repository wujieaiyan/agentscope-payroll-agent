package io.agentscope.examples.payroll.usage;

import java.time.LocalDateTime;
import java.util.List;

/** 看板一次查询的全部数据：KPI、趋势、维度分布与最近调用。 */
public record LlmUsageOverview(
        LocalDateTime from,
        LocalDateTime to,
        String granularity,
        LlmUsageSummary summary,
        List<LlmUsagePoint> series,
        List<LlmUsageSlice> byModel,
        List<LlmUsageSlice> byPlane,
        List<LlmUsageSlice> byAgent,
        List<LlmUsageSlice> byUser,
        List<LlmUsageRecord> recent) {
}
