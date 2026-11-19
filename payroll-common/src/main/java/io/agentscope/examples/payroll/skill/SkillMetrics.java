package io.agentscope.examples.payroll.skill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 技能规模化指标（规格 4.7）：目录构建耗时、正文加载与缓存命中、提示词注入/跳过、
 * 激活未命中与智能体缓存重建。
 *
 * <p>沿用 {@link Metrics#globalRegistry}，与管理台 Actuator 指标线一致；命中率与最近一次
 * 目录构建耗时同时保留进程内计数，供技能页顶部规模指标直接展示。
 */
public final class SkillMetrics {

    public static final String CATALOG_BUILD = "skill.catalog.build.duration";
    public static final String CONTENT_LOAD = "skill.content.load.count";
    public static final String CONTENT_LOAD_SIZE = "skill.content.load.summary";
    public static final String CONTENT_HIT_RATIO = "skill.content.cache.hit_ratio";
    public static final String PROMPT_INJECTED = "skill.prompt.injected.count";
    public static final String PROMPT_SKIPPED = "skill.prompt.skipped.count";
    public static final String ACTIVATION_MISS = "skill.activation.miss.count";
    public static final String AGENT_CACHE_REBUILD = "agent.cache.rebuild.count";

    private static final CacheStats CACHE_STATS = new CacheStats();
    private static final AtomicLong LAST_CATALOG_BUILD_MILLIS = new AtomicLong();
    private static final Map<String, Timer> TIMERS = new ConcurrentHashMap<>();
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, DistributionSummary> SUMMARIES = new ConcurrentHashMap<>();
    private static final Counter PROMPT_INJECTED_COUNTER = counter(PROMPT_INJECTED);
    private static final Counter PROMPT_SKIPPED_COUNTER = counter(PROMPT_SKIPPED);
    private static final Counter ACTIVATION_MISS_COUNTER = counter(ACTIVATION_MISS);

    static {
        Metrics.gauge(CONTENT_HIT_RATIO, CACHE_STATS, CacheStats::hitRatio);
    }

    private SkillMetrics() {
    }

    public static void catalogBuilt(String source, long nanos) {
        long elapsed = Math.max(0L, nanos);
        LAST_CATALOG_BUILD_MILLIS.set(TimeUnit.NANOSECONDS.toMillis(elapsed));
        timer(CATALOG_BUILD, "source", normalize(source)).record(elapsed, TimeUnit.NANOSECONDS);
    }

    /** 正文加载：命中本地缓存只计 hit，回源读取同时记录字符数。 */
    public static void contentLoaded(String source, boolean hit, long chars) {
        String origin = normalize(source);
        (hit ? CACHE_STATS.hits : CACHE_STATS.misses).increment();
        counter(CONTENT_LOAD, "source", origin, "result", hit ? "hit" : "miss").increment();
        if (!hit) {
            summary(CONTENT_LOAD_SIZE, origin).record(Math.max(0L, chars));
        }
    }

    public static void promptInjected(int count) {
        if (count > 0) {
            PROMPT_INJECTED_COUNTER.increment(count);
        }
    }

    public static void promptSkipped(int count) {
        if (count > 0) {
            PROMPT_SKIPPED_COUNTER.increment(count);
        }
    }

    public static void activationMiss() {
        ACTIVATION_MISS_COUNTER.increment();
    }

    public static void agentCacheRebuild(String reason) {
        counter(AGENT_CACHE_REBUILD, "reason", normalize(reason)).increment();
    }

    public static double contentCacheHitRatio() {
        return CACHE_STATS.hitRatio();
    }

    public static long lastCatalogBuildMillis() {
        return LAST_CATALOG_BUILD_MILLIS.get();
    }

    private static Timer timer(String name, String tagKey, String tagValue) {
        return TIMERS.computeIfAbsent(
                name + '|' + tagValue,
                ignored ->
                        Timer.builder(name)
                                .description("技能目录构建耗时")
                                .tag(tagKey, tagValue)
                                .register(Metrics.globalRegistry));
    }

    private static Counter counter(String name, String... tags) {
        String key = name + '|' + String.join("|", tags);
        return COUNTERS.computeIfAbsent(
                key,
                ignored -> {
                    Counter.Builder builder = Counter.builder(name);
                    if (tags.length > 0) {
                        builder.tags(tags);
                    }
                    return builder.register(Metrics.globalRegistry);
                });
    }

    private static DistributionSummary summary(String name, String source) {
        return SUMMARIES.computeIfAbsent(
                name + '|' + source,
                ignored ->
                        DistributionSummary.builder(name)
                                .description("技能正文字符数")
                                .tag("source", source)
                                .register(Metrics.globalRegistry));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CacheStats {
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();

        private double hitRatio() {
            long total = hits.sum() + misses.sum();
            return total == 0 ? 0D : (double) hits.sum() / total;
        }
    }
}
