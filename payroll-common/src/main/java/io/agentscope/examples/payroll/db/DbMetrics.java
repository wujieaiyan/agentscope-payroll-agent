package io.agentscope.examples.payroll.db;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 数据库层指标（P0-3 连接获取 + P0-9 Mapper 调用次数/语句耗时）。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>{@code payroll.db.connection.acquired}：{@link PayrollDatabase#connection()} 每次调用 +1；
 *   <li>{@code payroll.db.mapper.calls}：每条 Mapper 方法进入一次 +1，tag {@code statement=短名}；
 *   <li>{@code payroll.db.statement.duration}：同一入口的执行耗时，进程内保留最近 2048 次样本算 P95。
 * </ul>
 *
 * <p>Prometheus 与本地断言读同一份计数：Micrometer 侧走 globalRegistry，进程内基线读
 * 这里的 LongAdder/样本窗，不依赖注册表是否装配（纯 JUnit 环境下注册表是 no-op）。
 */
public final class DbMetrics {

    public static final String CONNECTION_ACQUIRED = "payroll.db.connection.acquired";
    public static final String MAPPER_CALLS = "payroll.db.mapper.calls";
    public static final String STATEMENT_DURATION = "payroll.db.statement.duration";

    /** 每条语句保留的耗时样本数；覆盖一次基线测量的全部调用，超出后丢最旧样本。 */
    static final int STATEMENT_WINDOW = 2048;

    private static final LongAdder CONNECTION_ACQUIRED_COUNT = new LongAdder();
    private static final LongAdder MAPPER_CALL_COUNT = new LongAdder();
    private static final Map<String, LongAdder> MAPPER_CALLS_BY_STATEMENT = new ConcurrentHashMap<>();
    private static final Map<String, Counter> MAPPER_COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, Timer> STATEMENT_TIMERS = new ConcurrentHashMap<>();
    private static final Map<String, StatementWindow> STATEMENT_WINDOWS = new ConcurrentHashMap<>();

    private static final Counter CONNECTION_ACQUIRED_COUNTER =
            Counter.builder(CONNECTION_ACQUIRED)
                    .description("PayrollDatabase.connection() 调用次数")
                    .register(Metrics.globalRegistry);

    private DbMetrics() {
    }

    public static void connectionAcquired() {
        CONNECTION_ACQUIRED_COUNT.increment();
        CONNECTION_ACQUIRED_COUNTER.increment();
    }

    public static long connectionAcquiredCount() {
        return CONNECTION_ACQUIRED_COUNT.sum();
    }

    public static void mapperCalled(String statementId) {
        MAPPER_CALL_COUNT.increment();
        MAPPER_CALLS_BY_STATEMENT
                .computeIfAbsent(statementId, key -> new LongAdder())
                .increment();
        MAPPER_COUNTERS
                .computeIfAbsent(
                        statementId,
                        key ->
                                Counter.builder(MAPPER_CALLS)
                                        .description("Mapper 方法调用次数")
                                        .tag("statement", key)
                                        .register(Metrics.globalRegistry))
                .increment();
    }

    public static void statementExecuted(String statementId, long elapsedNanos) {
        STATEMENT_WINDOWS
                .computeIfAbsent(statementId, key -> new StatementWindow())
                .record(elapsedNanos);
        STATEMENT_TIMERS
                .computeIfAbsent(
                        statementId,
                        key ->
                                Timer.builder(STATEMENT_DURATION)
                                        .description("Mapper 语句执行耗时")
                                        .tag("statement", key)
                                        .publishPercentiles(0.5, 0.95, 0.99)
                                        .register(Metrics.globalRegistry))
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public static long mapperCallCount() {
        return MAPPER_CALL_COUNT.sum();
    }

    public static long mapperCallCount(String statementId) {
        LongAdder adder = MAPPER_CALLS_BY_STATEMENT.get(statementId);
        return adder == null ? 0L : adder.sum();
    }

    public static Map<String, Long> mapperCallsByStatement() {
        Map<String, Long> snapshot = new java.util.TreeMap<>();
        MAPPER_CALLS_BY_STATEMENT.forEach((key, value) -> snapshot.put(key, value.sum()));
        return Map.copyOf(snapshot);
    }

    public static long statementCount(String statementId) {
        StatementWindow window = STATEMENT_WINDOWS.get(statementId);
        return window == null ? 0L : window.size();
    }

    /** 最近 {@link #STATEMENT_WINDOW} 次样本的 P95（纳秒）；无样本返回 0。 */
    public static long statementP95Nanos(String statementId) {
        StatementWindow window = STATEMENT_WINDOWS.get(statementId);
        return window == null ? 0L : window.percentile(0.95);
    }

    public static List<String> statementIds() {
        return List.copyOf(new java.util.TreeSet<>(STATEMENT_WINDOWS.keySet()));
    }

    /** 仅基线测量与诊断用：清零进程内计数与样本窗（Micrometer 侧保留单调递增）。 */
    public static void reset() {
        CONNECTION_ACQUIRED_COUNT.reset();
        MAPPER_CALL_COUNT.reset();
        MAPPER_CALLS_BY_STATEMENT.clear();
        STATEMENT_WINDOWS.clear();
    }

    /** 固定容量环形样本窗，够算基线 P95 又不会随进程时长无限增长。 */
    private static final class StatementWindow {

        private final long[] samples = new long[STATEMENT_WINDOW];
        private int next;
        private int filled;

        synchronized void record(long nanos) {
            samples[next] = nanos;
            next = (next + 1) % STATEMENT_WINDOW;
            if (filled < STATEMENT_WINDOW) {
                filled++;
            }
        }

        synchronized int size() {
            return filled;
        }

        /** 未写满时有效样本恰为 {@code samples[0..filled)}；写满后整窗有效。 */
        synchronized long percentile(double percentile) {
            if (filled == 0) {
                return 0L;
            }
            long[] sorted = new long[filled];
            System.arraycopy(samples, 0, sorted, 0, filled);
            java.util.Arrays.sort(sorted);
            int index = (int) Math.ceil(percentile * filled) - 1;
            return sorted[Math.max(0, Math.min(index, filled - 1))];
        }
    }
}