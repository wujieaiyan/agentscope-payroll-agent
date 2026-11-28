package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2LlmUsageStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.service.LlmUsageService;
import io.agentscope.examples.payroll.usage.LlmUsageOverview;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageSlice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmUsageStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    private H2LlmUsageStore store;
    private LlmUsageService service;

    @BeforeEach
    void setUp() {
        store = PayrollMappers.forDatabase(PayrollDatabase.inMemory("usage-" + System.nanoTime())).llmUsageStore();
        service = new LlmUsageService(store);
    }

    @Test
    @DisplayName("写入后按时间倒序查询，并保留全部归因字段")
    void insertAndFindRoundTrip() {
        long first = insert(1, NOW.minusHours(2), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        long second = insert(2, NOW.minusMinutes(30), "qwen2.5:3b", "控制面", "control", "薪酬助手", "8", false);

        List<LlmUsageRecord> rows =
                store.find(LlmUsageQuery.lastHours(3, NOW, null, null, null, null), 100);

        assertEquals(2, rows.size());
        assertEquals(second, rows.get(0).id());
        assertEquals(first, rows.get(1).id());
        LlmUsageRecord latest = rows.get(0);
        assertEquals("qwen2.5:3b", latest.model());
        assertEquals("控制面", latest.plane());
        assertEquals("ollama", latest.provider());
        assertEquals("control", latest.agentId());
        assertEquals("薪酬助手", latest.agentName());
        assertEquals("implementer", latest.role());
        assertEquals("dev.task", latest.stageKey());
        assertEquals(2L, latest.runId());
        assertEquals(8, latest.promptTokens());
        assertEquals(4, latest.completionTokens());
        assertEquals(12, latest.totalTokens());
        assertEquals(240, latest.latencyMs());
        assertFalse(latest.success());
        assertEquals("IllegalStateException", latest.errorType());
    }

    @Test
    @DisplayName("模型/使用面/智能体/用户过滤条件可组合")
    void filtersByDimensions() {
        insert(1, NOW.minusHours(1), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        insert(2, NOW.minusMinutes(50), "qwen2.5:3b", "轻量面", "router", "路由", "8", true);
        insert(3, NOW.minusMinutes(40), "deepseek-coder", "编码面", "implementer", "开发", "7", true);

        assertEquals(
                2,
                store.find(LlmUsageQuery.lastHours(3, NOW, "qwen2.5:3b", null, null, null), 100).size());
        assertEquals(
                1,
                store.find(LlmUsageQuery.lastHours(3, NOW, null, "编码面", null, null), 100).size());
        assertEquals(
                1,
                store.find(LlmUsageQuery.lastHours(3, NOW, null, null, "router", null), 100).size());
        assertEquals(
                1,
                store.find(LlmUsageQuery.lastHours(3, NOW, null, null, "开发", null), 100).size());
        assertEquals(
                2,
                store.find(LlmUsageQuery.lastHours(3, NOW, null, null, null, "7"), 100).size());
    }

    @Test
    @DisplayName("时间窗左闭右开，窗口外记录不返回")
    void respectsTimeWindow() {
        insert(1, NOW.minusHours(5), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        insert(2, NOW.minusMinutes(10), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);

        assertEquals(
                1, store.find(LlmUsageQuery.lastHours(2, NOW, null, null, null, null), 100).size());
    }

    @Test
    @DisplayName("看板聚合：KPI、按小时补零、Top 分布与最近调用")
    void serviceAggregatesOverview() {
        insert(1, NOW.minusHours(2).plusMinutes(5), "qwen2.5:3b", "控制面", "control", "薪酬助手", "7", true);
        insert(2, NOW.minusHours(1), "qwen2.5:3b", "轻量面", "router", "路由", "8", true);
        insert(3, NOW.minusMinutes(20), "deepseek-coder", "编码面", "implementer", "开发", "7", false);

        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(3, NOW, null, null, null, null), Map.of("7", "王五"));

        assertEquals("hour", overview.granularity());
        assertEquals(3, overview.summary().calls());
        assertEquals(2, overview.summary().successes());
        assertEquals(1, overview.summary().failures());
        assertEquals(3 * 12, overview.summary().totalTokens());
        assertEquals(240, overview.summary().avgLatencyMs());
        assertEquals(2, overview.summary().activeUsers());
        assertEquals(3, overview.summary().activeAgents());

        assertEquals(3, overview.series().size());
        assertEquals(24, overview.series().get(2).totalTokens());
        assertEquals(1, overview.series().get(2).failures());

        List<LlmUsageSlice> byModel = overview.byModel();
        assertEquals("qwen2.5:3b", byModel.get(0).key());
        assertEquals(24, byModel.get(0).totalTokens());
        assertEquals(2, byModel.get(0).calls());

        LlmUsageSlice controlPlane =
                overview.byPlane().stream()
                        .filter(slice -> "控制面".equals(slice.key()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(12, controlPlane.totalTokens());
        assertEquals(1, controlPlane.calls());
        assertEquals("开发", overview.byAgent().get(0).label());
        assertEquals("王五", overview.byUser().get(0).label());
        assertEquals(3, overview.recent().size());
        assertEquals(overview.recent().get(0).id(), 3L);
    }

    @Test
    @DisplayName("空窗口返回全零 KPI 与空分布")
    void emptyWindow() {
        LlmUsageOverview overview =
                service.overview(
                        LlmUsageQuery.lastHours(1, NOW, null, null, null, null), Map.of());

        assertEquals(0, overview.summary().calls());
        assertEquals(0, overview.summary().totalTokens());
        assertTrue(overview.byModel().isEmpty());
        assertTrue(overview.recent().isEmpty());
        assertEquals(0, overview.summary().avgLatencyMs());
    }

    private long insert(
            long runId,
            LocalDateTime occurredAt,
            String model,
            String plane,
            String agentId,
            String agentName,
            String userId,
            boolean success) {
        return store.insert(
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
                        8,
                        4,
                        2,
                        12,
                        240,
                        success,
                        success ? null : "IllegalStateException",
                        "reply-" + runId));
    }
}
