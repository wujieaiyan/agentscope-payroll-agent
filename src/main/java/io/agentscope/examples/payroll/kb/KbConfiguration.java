package io.agentscope.examples.payroll.kb;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识库入库线程池；任务行先落库再提交，重启后未完成任务可手动重试。 */
@Configuration
public class KbConfiguration {

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
}
