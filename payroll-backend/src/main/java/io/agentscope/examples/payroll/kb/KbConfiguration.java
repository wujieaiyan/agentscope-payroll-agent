package io.agentscope.examples.payroll.kb;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Bean(name = "kbIngestExecutor", destroyMethod = "shutdown")
    public ExecutorService kbIngestExecutor(
            @Value("${payroll.kb.task-threads:2}") int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(
                Math.max(1, Math.min(threads, 8)),
                runnable -> {
                    Thread thread = new Thread(runnable, "kb-ingest-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
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
