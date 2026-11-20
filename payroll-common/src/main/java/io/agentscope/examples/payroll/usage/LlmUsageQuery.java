package io.agentscope.examples.payroll.usage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/** 看板查询条件：时间窗为左闭右开，其余字段为空表示不过滤。 */
public record LlmUsageQuery(
        LocalDateTime from,
        LocalDateTime to,
        String model,
        String plane,
        String agent,
        String userId) {

    public LlmUsageQuery {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("用量查询时间窗无效");
        }
    }

    /** 看板时间范围：近 24 小时 / 7 天 / 30 天，未知取值回落 24 小时。 */
    public static LlmUsageQuery range(
            String range,
            LocalDateTime now,
            String model,
            String plane,
            String agent,
            String userId) {
        LocalDateTime end = now == null ? LocalDateTime.now() : now;
        String value = range == null ? "" : range.trim().toLowerCase(Locale.ROOT);
        Duration window =
                switch (value) {
                    case "7d" -> Duration.ofDays(7);
                    case "30d" -> Duration.ofDays(30);
                    default -> Duration.ofHours(24);
                };
        return new LlmUsageQuery(
                end.minus(window), end, blankToNull(model), blankToNull(plane), blankToNull(agent),
                blankToNull(userId));
    }

    public static LlmUsageQuery lastHours(
            int hours,
            LocalDateTime now,
            String model,
            String plane,
            String agent,
            String userId) {
        LocalDateTime end = now == null ? LocalDateTime.now() : now;
        return new LlmUsageQuery(
                end.minusHours(Math.max(1, hours)),
                end,
                blankToNull(model),
                blankToNull(plane),
                blankToNull(agent),
                blankToNull(userId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
