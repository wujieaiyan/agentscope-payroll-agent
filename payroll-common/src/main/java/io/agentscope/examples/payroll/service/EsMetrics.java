package io.agentscope.examples.payroll.service;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elasticsearch 客户端耗时：知识库检索/写入、历史记忆、路由意图三处共用，按 {@code op} 区分。
 *
 * <p>只在真正发起请求的客户端记账，调用方无感知；ES 不可用时请求本身仍按原语义降级。
 */
public final class EsMetrics {

    public static final String DURATION = "payroll.es.duration";

    private static final Map<String, Timer> timers = new ConcurrentHashMap<>();

    private EsMetrics() {}

    public static long start() {
        return System.nanoTime();
    }

    /** 毫秒级耗时写入 {@code op=search|index|refresh|delete|admin}。 */
    public static void record(String op, long startNanos) {
        if (startNanos <= 0) {
            return;
        }
        String tag = op == null || op.isBlank() ? "unknown" : op.trim();
        long millis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        timers.computeIfAbsent(
                        tag,
                        key ->
                                Timer.builder(DURATION)
                                        .description("Elasticsearch 客户端请求耗时")
                                        .publishPercentileHistogram()
                                        .tag("op", key)
                                        .register(Metrics.globalRegistry))
                .record(Duration.ofMillis(millis));
    }
}
