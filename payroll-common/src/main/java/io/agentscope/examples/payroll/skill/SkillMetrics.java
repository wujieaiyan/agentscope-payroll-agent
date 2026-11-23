package io.agentscope.examples.payroll.skill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
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
    public static final String CATALOG_QUERY_ROWS = "skill.catalog.query.rows";
    public static final String CONTENT_LOAD = "skill.content.load.count";
    public static final String CONTENT_LOAD_SIZE = "skill.content.load.summary";
    public static final String CONTENT_HIT_RATIO = "skill.content.cache.hit_ratio";
    public static final String PROMPT_INJECTED = "skill.prompt.injected.count";
    public static final String PROMPT_SKIPPED = "skill.prompt.skipped.count";
    public static final String ACTIVATION_MISS = "skill.activation.miss.count";
    public static final String AGENT_CACHE_REBUILD = "agent.cache.rebuild.count";
    public static final String SOURCE_FALLBACK = "skill.source.fallback.count";
    public static final String INVALIDATIONS = "skill.invalidations.count";
    public static final String INVALIDATION_LAG = "skill.invalidation.lag";

    private static final CacheStats CACHE_STATS = new CacheStats();
    private static final AtomicLong LAST_CATALOG_BUILD_MILLIS = new AtomicLong();
    private static final Map<String, Timer> TIMERS = new ConcurrentHashMap<>();
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, DistributionSummary> SUMMARIES = new ConcurrentHashMap<>();
    /** Gauge 绑的活对象：强引用必须留在这里，Micrometer 对绑定的对象只持弱引用。 */
    private static final Map<String, AtomicLong> CATALOG_ROWS = new ConcurrentHashMap<>();
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

    /**
     * 目录那次查询返回的行数（规格 §15：db 源应等于技能数，用来抓退化成 N+1 或漏行）。
     *
     * <p>读失败时不要调用：Gauge 是最后一次成功读取的值，把失败记成 0 行看起来像「库里真的没技能」。
     */
    public static void catalogQueried(String source, long rows) {
        if (rows < 0L) {
            return;
        }
        String origin = normalize(source);
        CATALOG_ROWS.computeIfAbsent(origin, SkillMetrics::registerRowsGauge).set(rows);
    }

    private static AtomicLong registerRowsGauge(String source) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(CATALOG_QUERY_ROWS, value, AtomicLong::doubleValue)
                .description("技能目录那次查询返回的行数")
                .tag("source", source)
                .register(Metrics.globalRegistry);
        return value;
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

    /**
     * 技能源不可用、这次读取由别的源顶上（规格 §7.1）。
     *
     * <p>稳态必须恒为 0：非零意味着运行期读到的角色说明不是配置里那一份，是 P1 告警而不是提示。
     *
     * @param from 不可用的来源
     * @param to 最终提供内容的来源；整表合并类读取记 {@code composite}，无人兜住记 {@code none}
     */
    public static void sourceFallback(String from, String to) {
        counter(SOURCE_FALLBACK, "from", normalize(from), "to", normalize(to)).increment();
    }

    /**
     * 技能目录快照被丢弃（规格 §10）。
     *
     * @param trigger {@code write}=本节点管理写后立即失效，{@code poll}=轮询发现别的节点写过
     */
    public static void invalidation(String trigger) {
        counter(INVALIDATIONS, "trigger", normalize(trigger)).increment();
    }

    /**
     * 跨实例失效滞后：本节点发现聚合 revision 变化时，距库内最后一次写入过了多久（§10 门槛 P95 ≤ 20s）。
     *
     * <p>只有轮询腿调用：同进程写入不跨节点，报出来会把 P95 拉低成一个假的好消息。
     *
     * <p>负数截零——起点 {@code updated_at} 取数据库服务器时钟，终点取本节点时钟，节点间几秒的
     * 倒挂是常态而不是异常，留着负数这个 P95 无法解释。
     */
    public static void invalidationLag(long millis) {
        // 不带标签：滞后只由轮询腿产生，加 trigger 等于多出一个恒为空的维度。
        timer(INVALIDATION_LAG, "跨实例技能目录失效滞后")
                .record(Math.max(0L, millis), TimeUnit.MILLISECONDS);
    }

    public static double contentCacheHitRatio() {
        return CACHE_STATS.hitRatio();
    }

    public static long lastCatalogBuildMillis() {
        return LAST_CATALOG_BUILD_MILLIS.get();
    }

    /**
     * 两个 Timer 都开分位数直方图：§15/§17 的 P95 门槛（A-01 目录构建、V-07 失效滞后）没有别的数据
     * 来源——不开的话 Prometheus 侧只有 count/sum/max，max 会被一次 Nacos 抖动永久钉住。
     */
    private static Timer timer(String name, String tagKey, String tagValue) {
        return TIMERS.computeIfAbsent(
                name + '|' + tagValue,
                ignored ->
                        Timer.builder(name)
                                .description("技能目录构建耗时")
                                .publishPercentileHistogram()
                                .tag(tagKey, tagValue)
                                .register(Metrics.globalRegistry));
    }

    /** 不带标签的 Timer，缓存键用裸名：带标签的那份键里一定有 {@code |}，两者不会撞。 */
    private static Timer timer(String name, String description) {
        return TIMERS.computeIfAbsent(
                name,
                ignored ->
                        Timer.builder(name)
                                .description(description)
                                .publishPercentileHistogram()
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
