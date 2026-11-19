package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(registry);
    }

    @Test
    @DisplayName("目录构建记录耗时计时器与最近一次毫秒数，来源标签统一小写")
    void recordsCatalogBuildTimer() {
        long before = count(SkillMetrics.CATALOG_BUILD, "source", "bench-catalog");

        SkillMetrics.catalogBuilt("  Bench-Catalog ", TimeUnit.MILLISECONDS.toNanos(3));

        Timer timer =
                registry
                        .find(SkillMetrics.CATALOG_BUILD)
                        .tag("source", "bench-catalog")
                        .timer();
        assertNotNull(timer);
        assertEquals(before + 1, timer.count());
        assertEquals(3, SkillMetrics.lastCatalogBuildMillis());
    }

    @Test
    @DisplayName("正文加载区分命中与回源，字符数只统计回源")
    void recordsContentLoads() {
        String source = "bench-content";

        SkillMetrics.contentLoaded(source, false, 120);
        SkillMetrics.contentLoaded(source, true, 120);

        assertEquals(1, count(SkillMetrics.CONTENT_LOAD, "source", source, "result", "miss"));
        assertEquals(1, count(SkillMetrics.CONTENT_LOAD, "source", source, "result", "hit"));
        DistributionSummary summary =
                registry
                        .find(SkillMetrics.CONTENT_LOAD_SIZE)
                        .tag("source", source)
                        .summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(120, summary.totalAmount());
    }

    @Test
    @DisplayName("缓存命中率随回源下降、随命中回升，且始终落在 0..1")
    void reportsHitRatioFromAllSources() {
        double before = SkillMetrics.contentCacheHitRatio();
        assertTrue(before >= 0D && before <= 1D);

        SkillMetrics.contentLoaded("bench-ratio-a", false, 10);
        double afterMiss = SkillMetrics.contentCacheHitRatio();
        assertTrue(afterMiss <= before, before + " -> " + afterMiss);

        SkillMetrics.contentLoaded("bench-ratio-a", true, 0);
        SkillMetrics.contentLoaded("bench-ratio-b", true, 0);
        double afterHits = SkillMetrics.contentCacheHitRatio();
        assertTrue(afterHits > afterMiss, afterMiss + " -> " + afterHits);
        assertTrue(afterHits <= 1D, String.valueOf(afterHits));
    }

    @Test
    @DisplayName("提示词注入/跳过与激活未命中按次累加，零值不计数")
    void recordsPromptAndActivationCounts() {
        long injected = count(SkillMetrics.PROMPT_INJECTED);
        long skipped = count(SkillMetrics.PROMPT_SKIPPED);
        long misses = count(SkillMetrics.ACTIVATION_MISS);

        SkillMetrics.promptInjected(3);
        SkillMetrics.promptInjected(0);
        SkillMetrics.promptSkipped(2);
        SkillMetrics.promptSkipped(-1);
        SkillMetrics.activationMiss();

        assertEquals(injected + 3, count(SkillMetrics.PROMPT_INJECTED));
        assertEquals(skipped + 2, count(SkillMetrics.PROMPT_SKIPPED));
        assertEquals(misses + 1, count(SkillMetrics.ACTIVATION_MISS));
    }

    @Test
    @DisplayName("智能体缓存重建按原因打标签，空原因记为 unknown")
    void tagsAgentCacheRebuildsByReason() {
        long initial = count(SkillMetrics.AGENT_CACHE_REBUILD, "reason", "initial");
        long unknown = count(SkillMetrics.AGENT_CACHE_REBUILD, "reason", "unknown");

        SkillMetrics.agentCacheRebuild(" initial ");
        SkillMetrics.agentCacheRebuild(null);

        assertEquals(initial + 1, count(SkillMetrics.AGENT_CACHE_REBUILD, "reason", "initial"));
        assertEquals(unknown + 1, count(SkillMetrics.AGENT_CACHE_REBUILD, "reason", "unknown"));
    }

    private long count(String name, String... tagPairs) {
        Counter counter = registry.find(name).tags(tagPairs).counter();
        return counter == null ? 0L : (long) counter.count();
    }
}
