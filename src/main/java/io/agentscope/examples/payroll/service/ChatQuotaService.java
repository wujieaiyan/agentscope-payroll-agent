package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 对话并发配额：限制单个用户同时进行的流式生成数量。
 *
 * <p>有 Redis 时计数跨实例共享，并按 TTL 自动回收异常退出遗留的计数；
 * 无 Redis 或 Redis 故障时回退到进程内计数。配额只用于保护模型资源，不参与权限判断。
 */
public final class ChatQuotaService {

    private static final Logger log = LoggerFactory.getLogger(ChatQuotaService.class);
    private static final String KEY_PREFIX = "payroll:chat:active:";

    private final StringRedisTemplate redis;
    private final int maxConcurrent;
    private final Duration ttl;
    private final Map<Long, AtomicInteger> local = new ConcurrentHashMap<>();

    public ChatQuotaService(StringRedisTemplate redis, int maxConcurrent) {
        this.redis = redis;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.ttl = Duration.ofMinutes(15);
    }

    public boolean tryAcquire(long userId) {
        if (redis != null) {
            String key = KEY_PREFIX + userId;
            try {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, ttl);
                }
                if (count != null && count <= maxConcurrent) {
                    return true;
                }
                redis.opsForValue().decrement(key);
                return false;
            } catch (RuntimeException e) {
                log.debug("Redis 对话配额不可用，回退进程内计数：{}", e.getMessage());
            }
        }
        AtomicInteger counter = local.computeIfAbsent(userId, ignored -> new AtomicInteger());
        return counter.incrementAndGet() <= maxConcurrent;
    }

    public void release(long userId) {
        if (redis != null) {
            String key = KEY_PREFIX + userId;
            try {
                Long count = redis.opsForValue().decrement(key);
                if (count != null && count <= 0) {
                    redis.delete(key);
                }
                return;
            } catch (RuntimeException e) {
                log.debug("释放 Redis 对话配额失败：{}", e.getMessage());
            }
        }
        AtomicInteger counter = local.get(userId);
        if (counter != null && counter.decrementAndGet() <= 0) {
            local.remove(userId, counter);
        }
    }
}
