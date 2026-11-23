package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.CatalogFingerprint;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨实例目录失效轮询（规格 §10）：比对全表聚合 revision，变了就丢弃本节点的目录快照。
 *
 * <p>只刷目录、不刷正文缓存。聚合 revision 是全表口径，任何一个技能被保存都会让它变；跟着清正文
 * 等于每改一个技能就把全部技能重新装配一遍，§7.1 的内容身份缓存白做。未变更的技能靠
 * {@code version_id + resource_hash} 继续命中。
 *
 * <p>轮询失败不中断调度：{@code scheduleWithFixedDelay} 一旦任务抛出就永久取消后续执行，一次网络抖动
 * 不能让本节点从此再也收不到失效。失败时指纹不前进，因此恢复后的第一轮一定会补上这次失效。
 */
public final class SkillInvalidationPoller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillInvalidationPoller.class);

    private final SkillRuntimeReader reader;
    private final Runnable onRevisionChange;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;

    /** 上一轮读到的聚合 revision；{@code null} 表示还没建立起基线。 */
    private String lastRevision;

    public SkillInvalidationPoller(
            SkillRuntimeReader reader, Runnable onRevisionChange, long pollMillis) {
        this(reader, onRevisionChange, pollMillis, System::currentTimeMillis);
    }

    SkillInvalidationPoller(
            SkillRuntimeReader reader,
            Runnable onRevisionChange,
            long pollMillis,
            LongSupplier clock) {
        if (reader == null) {
            throw new IllegalArgumentException("技能读端口必填");
        }
        if (onRevisionChange == null) {
            throw new IllegalArgumentException("目录失效回调必填");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时钟必填");
        }
        this.reader = reader;
        this.onRevisionChange = onRevisionChange;
        this.clock = clock;
        if (pollMillis <= 0) {
            this.scheduler = null;
            return;
        }
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "skill-invalidation-poll");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.scheduler.scheduleWithFixedDelay(
                this::pollQuietly, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        log.info("[Skill] 跨实例目录失效轮询已启用：每 {} 毫秒比对一次聚合 revision", pollMillis);
    }

    /**
     * 立即比对一次，供调度线程与测试调用。
     *
     * @return 本轮是否丢弃了目录快照。首轮只登记基线，恒为 {@code false}
     */
    public synchronized boolean pollOnce() {
        CatalogFingerprint fingerprint = reader.fingerprint();
        String previous = lastRevision;
        lastRevision = fingerprint.revision();
        if (previous == null || previous.equals(fingerprint.revision())) {
            return false;
        }
        onRevisionChange.run();
        long lag = clock.getAsLong() - fingerprint.lastWriteMillis();
        SkillMetrics.invalidation("poll");
        SkillMetrics.invalidationLag(lag);
        log.info("[Skill] 聚合 revision 变化，已丢弃技能目录快照（距库内最后写入 {} 毫秒）", lag);
        return true;
    }

    /** 调度线程入口：异常一律就地吞掉，交给下一轮。 */
    void pollQuietly() {
        try {
            pollOnce();
        } catch (PayrollUnavailableException e) {
            log.warn("[Skill] 技能目录指纹轮询失败，下一轮继续：{}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn(
                    "[Skill] 技能目录指纹轮询异常，下一轮继续：{}",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
