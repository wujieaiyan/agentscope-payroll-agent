package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.service.SandboxGateway;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据工作区状态的有界刷新器：只有「有人打开数据页」才会回写状态是不够的——
 * 初始化常常在页面关闭后才完成，列表会一直停在「初始化中」。
 *
 * <p>约束：只处理非终态且观测超过 {@link #MIN_AGE_MILLIS} 的快照，单轮最多 {@link #MAX_PER_TICK} 条；
 * 沙箱未启用直接不启动；终态写入后自然不再被查到。单实例守护线程，随容器关闭。
 * 多实例同时刷新只是重复查询，upsert 幂等，不做分布式锁。
 */
public final class SkillDataStateRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillDataStateRefresher.class);
    static final long TICK_SECONDS = 10L;
    static final long MIN_AGE_MILLIS = 10_000L;
    static final int MAX_PER_TICK = 20;

    private final SkillDataStateStore store;
    private final SandboxGateway gateway;
    private final ScheduledExecutorService executor;

    public SkillDataStateRefresher(SkillDataStateStore store, SandboxGateway gateway) {
        this.store = store;
        this.gateway = gateway;
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "skill-data-state-refresher");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public void start() {
        if (gateway == null || !gateway.enabled()) {
            log.info("[Skill] 沙箱未启用，数据状态刷新器不启动");
            return;
        }
        executor.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        log.info("[Skill] 数据状态刷新器已启动（{}s/轮，单轮 ≤{} 条）", TICK_SECONDS, MAX_PER_TICK);
    }

    private void tick() {
        try {
            refreshOnce();
        } catch (RuntimeException e) {
            log.warn("[Skill] 数据状态刷新失败：{}", e.getMessage());
        }
    }

    /** 单轮刷新，返回实际回写的条数；测试直接调用。 */
    public int refreshOnce() {
        if (gateway == null || !gateway.enabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<SkillDataState> pending = store.pendingBefore(now - MIN_AGE_MILLIS, MAX_PER_TICK);
        int updated = 0;
        for (SkillDataState state : pending) {
            try {
                SandboxGateway.SkillData observed = gateway.skillData(state.skillId());
                if (observed == null) {
                    continue;
                }
                SkillDataState next = SkillDataState.of(state.skillId(), observed, now);
                store.upsert(next);
                updated++;
            } catch (RuntimeException e) {
                // 单个技能查询失败不阻塞同批其他项；下轮再试。
                log.debug("[Skill] 刷新数据状态失败 skill={}：{}", state.skillId(), e.getMessage());
            }
        }
        return updated;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
