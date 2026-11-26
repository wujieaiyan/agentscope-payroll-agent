package io.agentscope.examples.payroll.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 有界执行器的排队与在途量：队列持续贴顶即容量不足，配合 429/降级指标定位瓶颈池。
 *
 * <p>执行器 bean 创建时绑定一次；同一 JVM 里重复注册相同 id 由 Micrometer 去重。
 */
public final class ExecutorMetrics {

    public static final String QUEUE = "payroll.executor.queue";
    public static final String ACTIVE = "payroll.executor.active";

    private ExecutorMetrics() {}

    /** pool 取 chat / tail / runtime / kbSearch / kbIngest / memoryRecall。 */
    public static void bind(String pool, ThreadPoolExecutor executor) {
        if (executor == null || pool == null || pool.isBlank()) {
            return;
        }
        String tag = pool.trim();
        Gauge.builder(QUEUE, executor, value -> value.getQueue().size())
                .description("执行器排队任务数")
                .tag("pool", tag)
                .register(Metrics.globalRegistry);
        Gauge.builder(ACTIVE, executor, value -> value.getActiveCount())
                .description("执行器在途任务数")
                .tag("pool", tag)
                .register(Metrics.globalRegistry);
    }
}
