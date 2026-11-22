package io.agentscope.examples.payroll.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮次续流核心指标。默认写入全局 {@link Metrics#globalRegistry}：Spring Boot 会把
 * Prometheus 注册表挂到全局，测试可用 {@link #useRegistry} 切换为独立注册表。
 *
 * <p>所有指标带 {@code deployment.mode} 标签，便于区分单机降级与集群快速失败。
 */
public final class TurnMetrics {

    public static final String GAP = "payroll.chat.turn.gap";
    public static final String DEGRADE = "payroll.chat.turn.degrade";
    public static final String LEASE_LOST = "payroll.chat.turn.lease.lost";
    public static final String EVENT_BYTES = "payroll.chat.turn.event.bytes";
    public static final String REPLAY_EVENTS = "payroll.chat.turn.replay.events";
    public static final String RESUME = "payroll.chat.turn.resume";
    public static final String STREAM_REJECTED = "payroll.chat.stream.rejected";
    public static final String QUOTA_REJECTED = "payroll.chat.quota.rejected";
    public static final String LEASE_RENEW_FAILED = "payroll.chat.lease.renew.failed";
    public static final String TURN_EXPIRED = "payroll.chat.turn.expired";
    public static final String SUBMIT_BUDGET_EXHAUSTED = "payroll.runtime.submit.budget.exhausted";
    public static final String EVENT_BUS_UNAVAILABLE = "payroll.chat.event.bus.unavailable";

    private static volatile MeterRegistry registry = Metrics.globalRegistry;
    private static volatile String deploymentMode = "single";
    private static volatile Counter gap;
    private static volatile Counter degrade;
    private static volatile Counter leaseLost;
    private static volatile DistributionSummary eventBytes;
    private static volatile DistributionSummary replayEvents;
    private static volatile Timer resume;
    private static volatile Counter streamRejected;
    private static volatile Counter quotaRejected;
    private static volatile Counter leaseRenewFailed;
    private static volatile Counter submitBudgetExhausted;
    private static volatile Counter eventBusUnavailable;
    private static final Map<String, Counter> turnExpired = new ConcurrentHashMap<>();

    private TurnMetrics() {}

    static void useRegistry(MeterRegistry value) {
        registry = value == null ? Metrics.globalRegistry : value;
        gap = null;
        degrade = null;
        leaseLost = null;
        eventBytes = null;
        replayEvents = null;
        resume = null;
        streamRejected = null;
        quotaRejected = null;
        leaseRenewFailed = null;
        submitBudgetExhausted = null;
        eventBusUnavailable = null;
        turnExpired.clear();
    }

    /** 启动时由装配层写入；必须在首个请求前调用。 */
    public static void configureDeploymentMode(String mode) {
        deploymentMode = mode == null || mode.isBlank() ? "single" : mode.trim();
    }

    public static String deploymentMode() {
        return deploymentMode;
    }

    /** 续流断点缺口：客户端游标早于仍保留的事件。 */
    public static void gap() {
        Counter counter = gap;
        if (counter == null) {
            counter = counter(GAP, "续流断点缺口次数").register(registry);
            gap = counter;
        }
        counter.increment();
    }

    /** 事件总线降级到进程内事件的操作次数。 */
    public static void degraded() {
        Counter counter = degrade;
        if (counter == null) {
            counter = counter(DEGRADE, "事件总线降级操作次数").register(registry);
            degrade = counter;
        }
        counter.increment();
    }

    /** 租约丢失导致轮次被终止的次数。 */
    public static void leaseLost() {
        Counter counter = leaseLost;
        if (counter == null) {
            counter = counter(LEASE_LOST, "租约丢失终止轮次次数").register(registry);
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
                            .tag("deployment.mode", deploymentMode)
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
                            .tag("deployment.mode", deploymentMode)
                            .register(registry);
            replayEvents = summary;
        }
        summary.record(count);
    }

    /** 对话执行队列已满被拒绝的请求数。 */
    public static void streamRejected() {
        Counter counter = streamRejected;
        if (counter == null) {
            counter = counter(STREAM_REJECTED, "对话执行队列满被拒绝的请求数").register(registry);
            streamRejected = counter;
        }
        counter.increment();
    }

    /** 续流追平耗时：从建立连接（或首次重放）到事件日志耗尽。 */
    public static void resumeReplay(long startedAtMillis) {
        Timer timer = resume;
        if (timer == null) {
            timer =
                    Timer.builder(RESUME)
                            .description("续流追平耗时")
                            .publishPercentileHistogram()
                            .tag("deployment.mode", deploymentMode)
                            .register(registry);
            resume = timer;
        }
        timer.record(Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - startedAtMillis)));
    }

    /** 配额已满被拒绝的请求数。 */
    public static void quotaRejected() {
        Counter counter = quotaRejected;
        if (counter == null) {
            counter = counter(QUOTA_REJECTED, "配额已满被拒绝的请求数").register(registry);
            quotaRejected = counter;
        }
        counter.increment();
    }

    /** 租约续约失败次数；持续增长说明 Redis 或网络异常。 */
    public static void leaseRenewFailed() {
        Counter counter = leaseRenewFailed;
        if (counter == null) {
            counter = counter(LEASE_RENEW_FAILED, "轮次租约续约失败次数").register(registry);
            leaseRenewFailed = counter;
        }
        counter.increment();
    }

    /** 轮次被判定失效：reason 区分 no-events / deadline / lease。 */
    public static void turnExpired(String reason) {
        String tag = reason == null || reason.isBlank() ? "unknown" : reason.trim();
        Counter counter =
                turnExpired.computeIfAbsent(
                        tag,
                        key ->
                                Counter.builder(TURN_EXPIRED)
                                        .description("轮次被判失效次数")
                                        .tag("deployment.mode", deploymentMode)
                                        .tag("reason", key)
                                        .register(registry));
        counter.increment();
    }

    /** 提交 runtime 的总预算耗尽次数。 */
    public static void submitBudgetExhausted() {
        Counter counter = submitBudgetExhausted;
        if (counter == null) {
            counter =
                    counter(SUBMIT_BUDGET_EXHAUSTED, "runtime 提交预算耗尽次数")
                            .register(registry);
            submitBudgetExhausted = counter;
        }
        counter.increment();
    }

    /** cluster 模式下事件总线不可用（直接失败而非降级）的次数。 */
    public static void eventBusUnavailable() {
        Counter counter = eventBusUnavailable;
        if (counter == null) {
            counter = counter(EVENT_BUS_UNAVAILABLE, "事件总线不可用次数").register(registry);
            eventBusUnavailable = counter;
        }
        counter.increment();
    }

    private static Counter.Builder counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .tag("deployment.mode", deploymentMode);
    }
}
