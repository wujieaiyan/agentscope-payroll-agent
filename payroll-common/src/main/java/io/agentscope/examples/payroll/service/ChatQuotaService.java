package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 对话并发配额：限制单个用户同时进行的流式生成数量。
 *
 * <p>有 Redis 时用单个 Lua 完成「检查容量 + 登记许可 + 刷新 TTL」，计数跨实例共享；
 * 每个许可在哈希中记录后端标记，释放只回收本后端持有的许可。single 模式 Redis 故障时
 * 回退进程内计数，cluster 模式直接拒绝新请求（503），避免上限按节点放大。
 */
public final class ChatQuotaService {

    private static final Logger log = LoggerFactory.getLogger(ChatQuotaService.class);
    private static final String KEY_PREFIX = "payroll:chat:active:";
    private static final String PERMIT_PREFIX = "permit:";

    /**
     * HLEN 即当前活跃数；未超限则登记一个许可并整键续期，超限直接返回 0。
     * 许可值记录后端标记，release 只删除本后端的许可，acquire/release 不会落到不同后端。
     */
    private static final RedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local active = redis.call('HLEN', KEYS[1])
                    if active >= tonumber(ARGV[1]) then
                      return 0
                    end
                    redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return active + 1
                    """,
                    Long.class);

    /** 删除一个属于本后端的许可；没有则返回 0，不会误删其它节点的计数。 */
    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local permits = redis.call('HKEYS', KEYS[1])
                    for i = 1, #permits do
                      local field = permits[i]
                      if string.sub(field, 1, #ARGV[1]) == ARGV[1]
                          and redis.call('HGET', KEYS[1], field) == ARGV[2] then
                        redis.call('HDEL', KEYS[1], field)
                        if redis.call('HLEN', KEYS[1]) == 0 then
                          redis.call('DEL', KEYS[1])
                        end
                        return 1
                      end
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final int maxConcurrent;
    private final Duration ttl;
    private final DeploymentMode mode;
    private final String backend;
    private final boolean failFast;
    private final Map<Long, AtomicInteger> local = new ConcurrentHashMap<>();

    public ChatQuotaService(StringRedisTemplate redis, int maxConcurrent) {
        this(redis, maxConcurrent, Duration.ofMinutes(35), DeploymentMode.SINGLE, "local");
    }

    public ChatQuotaService(
            StringRedisTemplate redis,
            int maxConcurrent,
            Duration ttl,
            DeploymentMode mode,
            String backend) {
        this(
                redis,
                maxConcurrent,
                ttl,
                mode,
                backend,
                mode != null && mode.cluster());
    }

    public ChatQuotaService(
            StringRedisTemplate redis,
            int maxConcurrent,
            Duration ttl,
            DeploymentMode mode,
            String backend,
            boolean failFast) {
        this.redis = redis;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(35) : ttl;
        this.mode = mode == null ? DeploymentMode.SINGLE : mode;
        this.backend = backend == null || backend.isBlank() ? "local" : backend.trim();
        // cluster 不允许回退本地计数，否则上限会按节点放大。
        this.failFast = this.mode.cluster() || failFast;
        if (this.mode.cluster() && redis == null) {
            throw new IllegalStateException("cluster 模式必须配置 Redis：对话配额不再回退进程内计数");
        }
    }

    /** 获取一个并发许可；超限返回 false。cluster 下 Redis 故障抛出 503。 */
    public boolean tryAcquire(long userId) {
        if (redis != null) {
            String key = KEY_PREFIX + userId;
            try {
                Long active =
                        redis.execute(
                                ACQUIRE_SCRIPT,
                                List.of(key),
                                Integer.toString(maxConcurrent),
                                PERMIT_PREFIX + UUID.randomUUID(),
                                backend,
                                Long.toString(ttl.toMillis()));
                if (active != null && active > 0) {
                    return true;
                }
                TurnMetrics.quotaRejected();
                return false;
            } catch (RuntimeException e) {
                if (failFast) {
                    throw new PayrollUnavailableException("对话配额服务不可用，请稍后重试", e);
                }
                log.warn("Redis 对话配额不可用，回退进程内计数：{}", e.getMessage());
            }
        }
        AtomicInteger counter = local.computeIfAbsent(userId, ignored -> new AtomicInteger());
        boolean acquired = counter.incrementAndGet() <= maxConcurrent;
        if (!acquired) {
            counter.decrementAndGet();
            TurnMetrics.quotaRejected();
        }
        return acquired;
    }

    /** 释放一个本后端的并发许可；重复释放或无许可时为空操作。 */
    public void release(long userId) {
        if (redis != null) {
            try {
                redis.execute(
                        RELEASE_SCRIPT, List.of(KEY_PREFIX + userId), PERMIT_PREFIX, backend);
                return;
            } catch (RuntimeException e) {
                // 释放失败由 TTL 兜底，异常不能掩盖调用方 finally 中的原始异常。
                log.warn("释放 Redis 对话配额失败，等待 TTL 回收：{}", e.getMessage());
            }
        }
        AtomicInteger counter = local.get(userId);
        if (counter != null && counter.decrementAndGet() <= 0) {
            local.remove(userId, counter);
        }
    }
}
