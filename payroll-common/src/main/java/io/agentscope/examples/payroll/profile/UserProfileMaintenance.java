package io.agentscope.examples.payroll.profile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取凭证清理：与 chat_turns 同周期（默认 90 天），只在非 runtime 节点执行，避免重复扫库。
 */
public final class UserProfileMaintenance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UserProfileMaintenance.class);
    private static final long FIRST_DELAY_MINUTES = 5;

    private final UserProfileStore store;
    private final Duration retention;
    private final ScheduledExecutorService scheduler;

    public UserProfileMaintenance(
            UserProfileStore store,
            int retentionDays,
            int intervalMinutes,
            boolean enabled) {
        this.store = store;
        this.retention = Duration.ofDays(Math.max(0, retentionDays));
        if (!enabled || store == null || retentionDays <= 0 || intervalMinutes <= 0) {
            this.scheduler = null;
            return;
        }
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "user-profile-cleanup");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleWithFixedDelay(
                this::run,
                FIRST_DELAY_MINUTES,
                intervalMinutes,
                TimeUnit.MINUTES);
        log.info(
                "[工作偏好] 抽取凭证清理已启用：保留 {} 天，每 {} 分钟执行",
                retentionDays,
                intervalMinutes);
    }

    private void run() {
        try {
            cleanup(LocalDateTime.now().minus(retention));
        } catch (RuntimeException e) {
            log.warn("[工作偏好] 清理抽取凭证失败：{}", e.getClass().getSimpleName());
        }
    }

    public int cleanup(LocalDateTime before) {
        int removed = store.cleanup(before);
        if (removed > 0) {
            log.info("[工作偏好] 清理过期抽取凭证 {} 条", removed);
        }
        return removed;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
