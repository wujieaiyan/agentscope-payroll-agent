package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.usage.LlmUsageOverview;
import io.agentscope.examples.payroll.usage.LlmUsagePoint;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageSlice;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.agentscope.examples.payroll.usage.LlmUsageSummary;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 用量看板聚合：KPI、趋势（24 小时按小时、7/30 天按天，空缺补零）、
 * 模型/使用面/智能体/用户分布与最近调用。
 *
 * <p>事实按行读取后在内存聚合，单次查询上限 {@value #MAX_ROWS} 行；
 * 本地/部门级部署的量级远低于该上限，换更细的 SQL 聚合收益有限。
 */
public final class LlmUsageService {

    static final int MAX_ROWS = 20_000;
    static final int TOP_SLICES = 8;
    static final int RECENT_ROWS = 12;

    private static final String UNKNOWN = "未标注";
    private static final String UNATTRIBUTED = "未归因";

    private final LlmUsageStore store;

    public LlmUsageService(LlmUsageStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public LlmUsageOverview overview(LlmUsageQuery query, Map<String, String> userNames) {
        LlmUsageQuery q = Objects.requireNonNull(query, "query");
        Map<String, String> names = userNames == null ? Map.of() : userNames;
        List<LlmUsageRecord> rows = store.find(q, MAX_ROWS);

        boolean hourly = Duration.between(q.from(), q.to()).toHours() <= 36;
        String granularity = hourly ? "hour" : "day";
        Map<LocalDateTime, PointAcc> buckets = buckets(q, hourly);

        SummaryAcc summary = new SummaryAcc();
        Map<String, SliceAcc> byModel = new LinkedHashMap<>();
        Map<String, SliceAcc> byPlane = new LinkedHashMap<>();
        Map<String, SliceAcc> byAgent = new LinkedHashMap<>();
        Map<String, SliceAcc> byUser = new LinkedHashMap<>();
        Set<String> activeUsers = new HashSet<>();
        Set<String> activeAgents = new HashSet<>();
        long latencySum = 0;
        long latencyCount = 0;

        for (LlmUsageRecord row : rows) {
            summary.add(row);
            if (row.latencyMs() > 0) {
                latencySum += row.latencyMs();
                latencyCount++;
            }
            String user = text(row.userId());
            if (user != null) {
                activeUsers.add(user);
            }
            String agent = agentKey(row.agentId(), row.agentName());
            if (!UNATTRIBUTED.equals(agent)) {
                activeAgents.add(agent);
            }
            PointAcc point = buckets.get(bucket(row.occurredAt(), hourly));
            if (point != null) {
                point.add(row);
            }
            accumulate(byModel, modelKey(row.model()), modelKey(row.model()), row);
            accumulate(byPlane, planeKey(row.plane()), planeKey(row.plane()), row);
            accumulate(byAgent, agent, agentLabel(row.agentId(), row.agentName()), row);
            String userKey = user == null ? UNATTRIBUTED : user;
            accumulate(byUser, userKey, userLabel(userKey, names), row);
        }

        LlmUsageSummary summaryView =
                new LlmUsageSummary(
                        summary.calls,
                        summary.successes,
                        summary.calls - summary.successes,
                        summary.promptTokens,
                        summary.completionTokens,
                        summary.cachedTokens,
                        summary.totalTokens,
                        latencyCount == 0 ? 0 : Math.round((double) latencySum / latencyCount),
                        activeUsers.size(),
                        activeAgents.size());

        List<LlmUsagePoint> series =
                buckets.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                entry ->
                                        new LlmUsagePoint(
                                                entry.getKey(),
                                                entry.getValue().promptTokens,
                                                entry.getValue().completionTokens,
                                                entry.getValue().totalTokens,
                                                entry.getValue().calls,
                                                entry.getValue().failures))
                        .toList();

        List<LlmUsageRecord> recent =
                rows.stream().limit(RECENT_ROWS).toList();

        return new LlmUsageOverview(
                q.from(),
                q.to(),
                granularity,
                summaryView,
                series,
                slices(byModel),
                slices(byPlane),
                slices(byAgent),
                slices(byUser),
                recent);
    }

    private static Map<LocalDateTime, PointAcc> buckets(LlmUsageQuery query, boolean hourly) {
        Map<LocalDateTime, PointAcc> buckets = new LinkedHashMap<>();
        LocalDateTime cursor = bucket(query.from(), hourly);
        for (int i = 0; i < 1_000 && cursor.isBefore(query.to()); i++) {
            buckets.put(cursor, new PointAcc());
            cursor = hourly ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return buckets;
    }

    private static LocalDateTime bucket(LocalDateTime value, boolean hourly) {
        if (value == null) {
            return LocalDateTime.MIN;
        }
        return hourly ? value.truncatedTo(ChronoUnit.HOURS) : value.toLocalDate().atStartOfDay();
    }

    private static void accumulate(
            Map<String, SliceAcc> slices, String key, String label, LlmUsageRecord row) {
        slices.computeIfAbsent(key, ignored -> new SliceAcc(label)).add(row);
    }

    private static List<LlmUsageSlice> slices(Map<String, SliceAcc> slices) {
        List<LlmUsageSlice> result = new ArrayList<>(slices.size());
        slices.forEach(
                (key, acc) ->
                        result.add(
                                new LlmUsageSlice(
                                        key,
                                        acc.label,
                                        acc.calls,
                                        acc.promptTokens,
                                        acc.completionTokens,
                                        acc.totalTokens,
                                        acc.failures)));
        result.sort(Comparator.comparingLong(LlmUsageSlice::totalTokens).reversed());
        return result.size() <= TOP_SLICES ? List.copyOf(result) : List.copyOf(result.subList(0, TOP_SLICES));
    }

    static String agentKey(String agentId, String agentName) {
        String id = text(agentId);
        if (id != null) {
            return id;
        }
        String name = text(agentName);
        return name == null ? UNATTRIBUTED : name;
    }

    static String agentLabel(String agentId, String agentName) {
        String name = text(agentName);
        if (name != null) {
            return name;
        }
        String id = text(agentId);
        return id == null ? UNATTRIBUTED : id;
    }

    static String userLabel(String key, Map<String, String> names) {
        if (UNATTRIBUTED.equals(key)) {
            return UNATTRIBUTED;
        }
        String resolved = names.get(key);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return key.matches("\\d+") ? "用户 #" + key : key;
    }

    private static String modelKey(String value) {
        String model = text(value);
        return model == null ? UNKNOWN : model;
    }

    private static String planeKey(String value) {
        String plane = text(value);
        return plane == null ? UNKNOWN : plane;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class PointAcc {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long calls;
        private long failures;

        private void add(LlmUsageRecord row) {
            promptTokens += row.promptTokens();
            completionTokens += row.completionTokens();
            totalTokens += row.totalTokens();
            calls++;
            if (!row.success()) {
                failures++;
            }
        }
    }

    private static final class SliceAcc {
        private final String label;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long calls;
        private long failures;

        private SliceAcc(String label) {
            this.label = label;
        }

        private void add(LlmUsageRecord row) {
            promptTokens += row.promptTokens();
            completionTokens += row.completionTokens();
            totalTokens += row.totalTokens();
            calls++;
            if (!row.success()) {
                failures++;
            }
        }
    }

    private static final class SummaryAcc {
        private long calls;
        private long successes;
        private long promptTokens;
        private long completionTokens;
        private long cachedTokens;
        private long totalTokens;

        private void add(LlmUsageRecord row) {
            calls++;
            if (row.success()) {
                successes++;
            }
            promptTokens += row.promptTokens();
            completionTokens += row.completionTokens();
            cachedTokens += row.cachedTokens();
            totalTokens += row.totalTokens();
        }
    }
}
