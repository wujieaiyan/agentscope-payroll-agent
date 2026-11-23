package io.agentscope.examples.payroll.kb;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        return executor;
    }

    /** 启动时恢复遗留的排队/运行中任务；关闭后保持原状，由人工在任务面板处理。 */
    @Bean
    public ApplicationRunner kbTaskRecoveryRunner(
            KbTaskService taskService,
            @Value("${payroll.kb.recovery-enabled:true}") boolean enabled) {
        return args -> {
            if (!enabled) {
                log.info("[知识库] 启动恢复已关闭，遗留入库任务保持原状");
                return;
            }
            try {
                taskService.recoverInterrupted();
            } catch (RuntimeException e) {
                log.warn("[知识库] 启动恢复失败：{}", e.getMessage());
            }
        };
    }
}
