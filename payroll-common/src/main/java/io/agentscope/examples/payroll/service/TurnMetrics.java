package io.agentscope.examples.payroll.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * 轮次续流核心指标。默认写入全局 {@link Metrics#globalRegistry}：Spring Boot 会把
 * Prometheus 注册表挂到全局，测试可用 {@link #useRegistry} 切换为独立注册表。
 */
public final class TurnMetrics {

    public static final String GAP = "payroll.chat.turn.gap";
    public static final String DEGRADE = "payroll.chat.turn.degrade";
    public static final String LEASE_LOST = "payroll.chat.turn.lease.lost";
    public static final String EVENT_BYTES = "payroll.chat.turn.event.bytes";
    public static final String REPLAY_EVENTS = "payroll.chat.turn.replay.events";
    public static final String RESUME = "payroll.chat.turn.resume";

    private static volatile MeterRegistry registry = Metrics.globalRegistry;
    private static volatile Counter gap;
    private static volatile Counter degrade;
    private static volatile Counter leaseLost;
    private static volatile DistributionSummary eventBytes;
    private static volatile DistributionSummary replayEvents;
    private static volatile Timer resume;

    private TurnMetrics() {}

    static void useRegistry(MeterRegistry value) {
        registry = value == null ? Metrics.globalRegistry : value;
        gap = null;
        degrade = null;
        leaseLost = null;
        eventBytes = null;
        replayEvents = null;
        resume = null;
    }

    /** 续流断点缺口：客户端游标早于仍保留的事件。 */
    public static void gap() {
        Counter counter = gap;
        if (counter == null) {
            counter =
                    Counter.builder(GAP)
                            .description("续流断点缺口次数")
                            .register(registry);
            gap = counter;
        }
        counter.increment();
    }

    /** 事件总线降级到进程内事件的操作次数。 */
    public static void degraded() {
        Counter counter = degrade;
        if (counter == null) {
            counter =
                    Counter.builder(DEGRADE)
                            .description("事件总线降级操作次数")
                            .register(registry);
            degrade = counter;
        }
        counter.increment();
    }

    /** 租约丢失导致轮次被终止的次数。 */
    public static void leaseLost() {
        Counter counter = leaseLost;
        if (counter == null) {
            counter =
                    Counter.builder(LEASE_LOST)
                            .description("租约丢失终止轮次次数")
                            .register(registry);
            leaseLost = counter;
        }
        counter.increment();
    }

    /** 单条事件负载的 UTF-8 字节数。 */
    public static void eventBytes(int bytes) {
        if (bytes <= 0) {
            return;
        }
        DistributionSummary summary = eventBytes;
        if (summary == null) {
            summary =
                    DistributionSummary.builder(EVENT_BYTES)
                            .description("单条轮次事件负载字节数")
                            .publishPercentileHistogram()
                            .register(registry);
            eventBytes = summary;
        }
        summary.record(bytes);
    }

    /** 单次续流重放的事件条数。 */
    public static void replayedEvents(long count) {
        if (count <= 0) {
            return;
        }
        DistributionSummary summary = replayEvents;
        if (summary == null) {
            summary =
                    DistributionSummary.builder(REPLAY_EVENTS)
                            .description("单次续流重放事件条数")
                            .publishPercentileHistogram()
                            .register(registry);
            replayEvents = summary;
        }
        summary.record(count);
    }

    /** 续流追平耗时：从建立连接（或首次重放）到事件日志耗尽。 */
    public static void resumeReplay(long startedAtMillis) {
        Timer timer = resume;
        if (timer == null) {
            timer =
                    Timer.builder(RESUME)
                            .description("续流追平耗时")
                            .publishPercentileHistogram()
                            .register(registry);
            resume = timer;
        }
        timer.record(Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - startedAtMillis)));
    }
}
