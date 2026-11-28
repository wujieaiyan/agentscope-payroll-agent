package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2LlmUsageStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.usage.LlmUsageOverview;
import io.agentscope.examples.payroll.usage.LlmUsagePoint;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageSlice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmUsageServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    private H2LlmUsageStore store;
    private LlmUsageService service;

    @BeforeEach
    void setUp() {
        store = PayrollMappers.forDatabase(PayrollDatabase.inMemory("usage-svc-" + System.nanoTime())).llmUsageStore();
        service = new LlmUsageService(store);
    }

    @Test
    @DisplayName("超过 36 小时的窗口按天聚合，空白天补零")
    void dayGranularityFillsEmptyDays() {
        insert(NOW.minusDays(5).withHour(9), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        insert(NOW.minusDays(2).withHour(10), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(24 * 7, NOW, null, null, null, null), Map.of());

        assertEquals("day", overview.granularity());
        List<LlmUsagePoint> series = overview.series();
        // 窗口起点 11-13 12:00 截到当日零点，到 11-20 零点，共 8 个桶
        assertEquals(8, series.size());
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), series.get(0).bucket());
        assertEquals(1, series.get(2).calls());
        assertEquals(12, series.get(2).totalTokens());
        assertEquals(0, series.get(3).calls());
        assertEquals(1, series.get(5).calls());
    }

    @Test
    @DisplayName("30 天窗口同样按天聚合")
    void thirtyDayWindowUsesDayBuckets() {
        insert(NOW.minusDays(20), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.range("30d", NOW, null, null, null, null), Map.of());

        assertEquals("day", overview.granularity());
        assertEquals(1, overview.summary().calls());
    }

    @Test
    @DisplayName("维度分布按 token 倒序且最多保留 8 项")
    void topSlicesCappedAtEight() {
        for (int i = 0; i < 10; i++) {
            insert(
                    NOW.minusMinutes(i),
                    "model-" + i,
                    "控制面",
                    "control",
                    "薪酬助手",
                    "7",
                    (i + 1) * 8,
                    (i + 1) * 4,
                    240,
                    true,
                    (long) i);
        }

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(1, NOW, null, null, null, null), Map.of());

        List<LlmUsageSlice> byModel = overview.byModel();
        assertEquals(8, byModel.size());
        assertEquals("model-9", byModel.get(0).key());
        assertEquals(120, byModel.get(0).totalTokens());
        assertEquals("model-2", byModel.get(7).key());
        assertEquals(36, byModel.get(7).totalTokens());
    }

    @Test
    @DisplayName("未标注模型/使用面与未归因智能体/用户使用兜底标签")
    void fallbackLabels() {
        insert(NOW.minusMinutes(3), null, " ", null, null, null, 8, 4, 240, true, null);
        insert(NOW.minusMinutes(2), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        insert(NOW.minusMinutes(1), "qwen2.5:3b", "控制面", "control", "薪酬助手", "8", true);

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(1, NOW, null, null, null, null),
                        Map.of("7", "王五"));

        LlmUsageSlice unknownModel = slice(overview.byModel(), "未标注");
        assertEquals("未标注", unknownModel.label());
        assertEquals("未标注", slice(overview.byPlane(), "未标注").label());
        assertEquals("未归因", slice(overview.byAgent(), "未归因").label());
        assertEquals("未归因", slice(overview.byUser(), "未归因").label());
        assertEquals("王五", slice(overview.byUser(), "7").label());
        assertEquals("用户 #8", slice(overview.byUser(), "8").label());
        assertEquals(2, overview.summary().activeUsers());
        assertEquals(1, overview.summary().activeAgents());
    }

    @Test
    @DisplayName("平均时延只统计有耗时记录的调用")
    void averageLatencySkipsZero() {
        insert(NOW.minusMinutes(3), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", 8, 4, 0, true, 1L);
        insert(NOW.minusMinutes(2), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", 8, 4, 100, true, 2L);
        insert(NOW.minusMinutes(1), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", 8, 4, 300, false, 3L);

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(1, NOW, null, null, null, null), Map.of());

        assertEquals(200, overview.summary().avgLatencyMs());
        assertEquals(3, overview.summary().calls());
        assertEquals(1, overview.summary().failures());
    }

    @Test
    @DisplayName("最近调用最多返回 12 条且按时间倒序")
    void recentCappedAtTwelve() {
        for (int i = 0; i < 15; i++) {
            insert(
                    NOW.minusMinutes(30L - i),
                    "qwen2.5:3b",
                    "控制面",
                    "control",
                    "薪酬助手",
                    "7",
                    8,
                    4,
                    240,
                    true,
                    (long) i);
        }

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(2, NOW, null, null, null, null), Map.of());

        assertEquals(15, overview.summary().calls());
        assertEquals(12, overview.recent().size());
        assertEquals(14L, overview.recent().get(0).runId());
        assertTrue(overview.recent().get(0).occurredAt().isAfter(overview.recent().get(11).occurredAt()));
    }

    private static LlmUsageSlice slice(List<LlmUsageSlice> slices, String key) {
        return slices.stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少分布项：" + key + "，实际=" + slices));
    }

    private void insert(
            LocalDateTime occurredAt,
            String model,
            String plane,
            String agentId,
            String agentName,
            String userId,
            boolean success) {
        insert(occurredAt, model, plane, agentId, agentName, userId, 8, 4, 240, success, null);
    }

    private void insert(
            LocalDateTime occurredAt,
            String model,
            String plane,
            String agentId,
            String agentName,
            String userId,
            int prompt,
            int completion,
            long latencyMs,
            boolean success,
            Long runId) {
        store.insert(
                new LlmUsageRecord(
                        0L,
                        occurredAt,
                        userId,
                        "42",
                        agentId,
                        agentName,
                        "implementer",
                        "dev.task",
                        runId,
                        model,
                        "ollama",
                        plane,
                        prompt,
                        completion,
                        0,
                        prompt + completion,
                        latencyMs,
                        success,
                        success ? null : "IllegalStateException",
                        "reply-" + runId));
    }
}
