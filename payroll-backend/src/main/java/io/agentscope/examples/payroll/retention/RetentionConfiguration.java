package io.agentscope.examples.payroll.retention;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.jobs.JobLeases;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 保留任务装配（C4）：启动只做 dry-run 统计，删除只在每日窗口内由持租约的节点执行。
 *
 * <p>多副本靠 {@code job_lease} 互斥：{@code retention.startup-stats} 保证启动统计只扫一次，
 * {@code retention.daily} 保证每日删除只跑一份；{@code retention-dry-run=true}（首版默认）
 * 时 cron 也只统计不删除。
 */
@Configuration
public class RetentionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RetentionConfiguration.class);

    /** 启动统计延迟，避开启动阶段的其他恢复任务。 */
    private static final long STARTUP_STATS_DELAY_SECONDS = 20L;

    @Bean
    public RetentionSettings retentionSettings(
            @Value("${payroll.jobs.retention-dry-run:true}") boolean dryRun,
            @Value("${payroll.chat.history-retention-days:365}") int chatDays,
            @Value("${payroll.usage.retention-days:180}") int usageDays,
            @Value("${payroll.kb.task-retention-days:180}") int kbDays,
            @Value("${payroll.jobs.retention-hour:2}") int hour,
            @Value("${payroll.jobs.retention-minute:30}") int minute,
            @Value("${payroll.jobs.retention-max-minutes:10}") int maxMinutes,
            @Value("${payroll.jobs.retention-batch-pause-ms:100}") long batchPauseMillis,
            @Value("${payroll.chat.attachment.dir:./data/chat-uploads}") String attachmentDir) {
        return new RetentionSettings(
                dryRun,
                chatDays,
                usageDays,
                kbDays,
                hour,
                minute,
                maxMinutes,
                batchPauseMillis,
                attachmentDir);
    }

    @Bean
    public RetentionStore retentionStore(PayrollDatabase database) {
        return new RetentionStore(database);
    }

    @Bean
    public RetentionService retentionService(
            RetentionStore retentionStore,
            RetentionSettings retentionSettings,
            MeterRegistry registry) {
        return new RetentionService(retentionStore, retentionSettings, registry);
    }

    @Bean(name = "retentionScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService retentionScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "retention-job");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * 启动统计 + 每日窗口。{@code node-role=runtime} 不参与（与轮次清理一致），
     * 其余节点由租约决定实际执行者。
     */
    @Bean
    public ApplicationRunner retentionJobRunner(
            RetentionService retentionService,
            RetentionSettings settings,
            JobLeases jobLeases,
            @Qualifier("retentionScheduler") ScheduledExecutorService scheduler,
            @Value("${payroll.jobs.retention-enabled:true}") boolean enabled,
            @Value("${payroll.node-role:all}") String nodeRole) {
        return args -> {
            if (!enabled) {
                log.info("[保留任务] 已关闭（payroll.jobs.retention-enabled=false）");
                return;
            }
            if ("runtime".equals(nodeRole)) {
                log.info("[保留任务] runtime 节点不执行清理，交由 web/all 节点");
                return;
            }
            scheduler.schedule(
                    () -> {
                        try {
                            jobLeases.runIfAcquired(
                                    "retention.startup-stats",
                                    retentionService::statsOnly);
                        } catch (RuntimeException e) {
                            log.warn("[保留任务] 启动统计失败：{}", e.getMessage());
                        }
                    },
                    STARTUP_STATS_DELAY_SECONDS,
                    TimeUnit.SECONDS);
            long initialDelay = initialDelayMillis(settings.hour(), settings.minute());
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            jobLeases.runIfAcquired(
                                    "retention.daily",
                                    lease ->
                                            retentionService.run(
                                                    settings.dryRun(), lease));
                        } catch (RuntimeException e) {
                            log.warn("[保留任务] 每日清理失败：{}", e.getMessage());
                        }
                    },
                    initialDelay,
                    TimeUnit.DAYS.toMillis(1),
                    TimeUnit.MILLISECONDS);
            log.info(
                    "[保留任务] 计划：每日 {:02d}:{:02d}，dry-run={}，聊天={}天，用量={}天，知识库={}天",
                    settings.hour(),
                    settings.minute(),
                    settings.dryRun(),
                    settings.chatDays(),
                    settings.usageDays(),
                    settings.kbDays());
        };
    }

    /** 距下一个 hour:minute 的毫秒数；当日已过则顺延到明天。 */
    static long initialDelayMillis(int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.toLocalDate().atTime(hour, minute);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Math.max(1_000L, next.atZone(zone).toInstant().toEpochMilli()
                - System.currentTimeMillis());
    }
}
