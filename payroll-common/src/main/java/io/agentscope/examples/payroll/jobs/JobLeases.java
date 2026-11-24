package io.agentscope.examples.payroll.jobs;

import io.agentscope.examples.payroll.db.JobLeaseStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动任务的租约门面：集群内只有一个节点执行，其余节点跳过。
 *
 * <p>长任务每批调用 {@link Lease#renew()}；续租失败说明租约已被接管，调用方必须立即中止，
 * 避免出现双写。{@code lease-enabled=false} 时退化为单节点直接执行（保留旧行为）。
 */
public final class JobLeases {

    private static final Logger log = LoggerFactory.getLogger(JobLeases.class);

    private final JobLeaseStore store;
    private final boolean enabled;
    private final String owner;
    private final int ttlSeconds;

    public JobLeases(JobLeaseStore store, boolean enabled, String nodeId, int ttlSeconds) {
        this.store = store;
        this.enabled = enabled && store != null;
        String node = nodeId == null || nodeId.isBlank() ? "local" : nodeId.trim();
        this.owner =
                node
                        + "-"
                        + ProcessHandle.current().pid()
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
        this.ttlSeconds = Math.max(10, ttlSeconds);
    }

    public boolean enabled() {
        return enabled;
    }

    public String owner() {
        return owner;
    }

    /** 抢租约；未开启时返回始终有效的本地租约。 */
    public Lease tryAcquire(String jobName) {
        if (!enabled) {
            return new Lease(jobName, true, true, this);
        }
        boolean acquired = store.tryAcquire(jobName, owner, ttlSeconds);
        if (acquired) {
            log.info("[租约] 已获得任务租约：job={}，owner={}", jobName, owner);
        } else {
            log.info("[租约] 任务由其他节点持有，跳过：job={}", jobName);
        }
        // local 必须跟着 enabled 走：恒为 true 会让 renew/release 短路，变成只抢不续不还。
        return new Lease(jobName, false, acquired, this);
    }

    /**
     * 抢到租约才执行；执行期间由调用方按批续租。返回是否实际执行。
     */
    public boolean runIfAcquired(String jobName, LeaseTask task) {
        Lease lease = tryAcquire(jobName);
        if (!lease.held()) {
            return false;
        }
        try {
            task.run(lease);
            return true;
        } finally {
            lease.release();
        }
    }

    /** 持有租约的任务句柄。 */
    public static final class Lease {

        private final String jobName;
        private final boolean local;
        private final boolean acquired;
        private final JobLeases parent;
        private volatile boolean held;

        private Lease(String jobName, boolean local, boolean acquired, JobLeases parent) {
            this.jobName = jobName;
            this.local = local;
            this.acquired = acquired;
            this.parent = parent;
            this.held = acquired;
        }

        public boolean held() {
            return held;
        }

        /** 续租；返回 false 表示租约丢失，任务必须中止。 */
        public boolean renew() {
            if (!held) {
                return false;
            }
            if (local) {
                return true;
            }
            try {
                boolean renewed = parent.store.renew(jobName, parent.owner, parent.ttlSeconds);
                if (!renewed) {
                    held = false;
                    log.warn("[租约] 续租失败，租约已被接管：job={}，owner={}", jobName, parent.owner);
                }
                return renewed;
            } catch (RuntimeException e) {
                held = false;
                log.warn("[租约] 续租异常，按丢失处理：job={}，原因={}", jobName, e.getMessage());
                return false;
            }
        }

        public void release() {
            if (!held) {
                return;
            }
            held = false;
            if (local) {
                return;
            }
            try {
                parent.store.release(jobName, parent.owner);
            } catch (RuntimeException e) {
                log.warn("[租约] 释放失败，等待 TTL 过期：job={}，原因={}", jobName, e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface LeaseTask {
        void run(Lease lease);
    }
}
