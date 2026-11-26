package io.agentscope.examples.payroll.kb.parse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 解析链指标：Web 侧远程客户端与 parser 进程各自记账，{@code engine} 区分 remote 与本地引擎。
 *
 * <p>失败原因只按异常类型与 HTTP 状态归类（timeout / busy / limit / oom / parse / rejected / canceled），
 * 不解析异常消息文本。
 */
public final class ParseMetrics {

    public static final String DURATION = "payroll.kb.parse.duration";
    public static final String FAILURES = "payroll.kb.parse.failures";
    public static final String QUEUE = "payroll.kb.parse.queue";

    private static final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private static final Map<String, Counter> failures = new ConcurrentHashMap<>();

    private ParseMetrics() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void duration(String outcome, String engine, long startNanos) {
        if (startNanos <= 0) {
            return;
        }
        String result = outcome == null || outcome.isBlank() ? "unknown" : outcome.trim();
        String engineTag = engine == null || engine.isBlank() ? "unknown" : engine.trim();
        long millis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        timers.computeIfAbsent(
                        result + "|" + engineTag,
                        key ->
                                Timer.builder(DURATION)
                                        .description("单文档解析耗时")
                                        .publishPercentileHistogram()
                                        .tag("outcome", result)
                                        .tag("engine", engineTag)
                                        .register(Metrics.globalRegistry))
                .record(Duration.ofMillis(millis));
    }

    /** 解析侧失败原因：按异常类型归类，不看消息文本。 */
    public static String reason(Throwable error) {
        if (error instanceof KbParseCanceled) {
            return "canceled";
        }
        if (error instanceof KbParseLimitException) {
            return "limit";
        }
        if (error instanceof KbParseRetryableException) {
            return error.getCause() instanceof HttpTimeoutException ? "timeout" : "503";
        }
        if (error instanceof VirtualMachineError) {
            return "oom";
        }
        return "parse";
    }

    /** 失败原因：timeout / 503 / limit / oom / parse / write / io / canceled。 */
    public static void failure(String reason) {
        String tag = reason == null || reason.isBlank() ? "unknown" : reason.trim();
        failures.computeIfAbsent(
                        tag,
                        key ->
                                Counter.builder(FAILURES)
                                        .description("解析失败次数")
                                        .tag("reason", key)
                                        .register(Metrics.globalRegistry))
                .increment();
    }

    /** parser 工作池排队量；直接交接队列恒为 0，超过 0 说明配置了有界队列。 */
    public static void bindQueue(ThreadPoolExecutor workers) {
        if (workers == null) {
            return;
        }
        Gauge.builder(QUEUE, workers, value -> value.getQueue().size())
                .description("解析工作池排队任务数")
                .register(Metrics.globalRegistry);
    }
}
