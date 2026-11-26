package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.chat.ChatAttachmentService;
import io.agentscope.examples.payroll.jobs.JobLeases;
import io.agentscope.examples.payroll.service.ExecutorMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识库入库线程池与启动恢复；任务行先落库再提交，重启后按状态自动收敛。 */
@Configuration
public class KbConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KbConfiguration.class);

    /** 有界队列 + AbortPolicy：满了立即拒绝，由任务服务落库为失败并对外返回 429。 */
    @Bean(name = "kbIngestExecutor", destroyMethod = "shutdown")
    public ExecutorService kbIngestExecutor(
            @Value("${payroll.kb.task-threads:2}") int threads,
            @Value("${payroll.kb.task-queue-capacity:64}") int queueCapacity) {
        int poolSize = Math.max(1, Math.min(threads, 8));
        BlockingQueue<Runnable> queue =
                new ArrayBlockingQueue<>(Math.max(1, Math.min(queueCapacity, 1000)));
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0L,
                        TimeUnit.MILLISECONDS,
                        queue,
                        runnable -> {
                            Thread thread =
                                    new Thread(runnable, "kb-ingest-" + counter.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("payroll.kb.ingest.queue.depth", queue, BlockingQueue::size)
                .description("知识库入库任务等待队列长度")
                .register(Metrics.globalRegistry);
        ExecutorMetrics.bind("kbIngest", executor);
        return executor;
    }

    /** 恢复巡检线程：启动即跑一次，之后按间隔巡检；单线程避免与自身竞争。 */
    @Bean(name = "kbRecoveryScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService kbRecoveryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "kb-recovery");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * 恢复 KB 入库与附件解析：启动 + 每 {@code recovery-interval-minutes} 巡检一次。
     *
     * <p>集群下只有持 {@code recovery.kb} 租约的节点执行；获锁节点在恢复前崩溃时，
     * 其他节点会在下次巡检接管，不再等到重启。
     */
    @Bean
    public ApplicationRunner kbTaskRecoveryRunner(
            KbTaskService taskService,
            ChatAttachmentService attachmentService,
            JobLeases jobLeases,
            @Qualifier("kbRecoveryScheduler") ScheduledExecutorService scheduler,
            @Value("${payroll.kb.recovery-enabled:true}") boolean enabled,
            @Value("${payroll.jobs.recovery-interval-minutes:5}") int intervalMinutes) {
        return args -> {
            if (!enabled) {
                log.info("[知识库] 恢复巡检已关闭，遗留任务保持原状");
                return;
            }
            long periodMillis = Math.max(1, intervalMinutes) * 60_000L;
            scheduler.scheduleAtFixedRate(
                    () ->
                            jobLeases.runIfAcquired(
                                    "recovery.kb",
                                    lease -> {
                                        try {
                                            taskService.recoverInterrupted();
                                        } catch (RuntimeException e) {
                                            log.warn("[知识库] 入库任务恢复失败：{}", e.getMessage());
                                        }
                                        try {
                                            attachmentService.recoverParsingAttachments();
                                        } catch (RuntimeException e) {
                                            log.warn("[附件] 解析恢复失败：{}", e.getMessage());
                                        }
                                    }),
                    0,
                    periodMillis,
                    TimeUnit.MILLISECONDS);
        };
    }
}
