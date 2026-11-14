package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 会话级互斥锁：同一会话在同一时刻只允许一轮回答，跨实例通过 Redis 协调。
 *
 * <p>Redis 不可用（或未启用 redis profile）时退化为进程内锁，至少保证单实例内不并发。
 * 锁带 TTL，实例崩溃后不会永久占用会话。
 */
public final class SessionLockService {

    private static final Logger log = LoggerFactory.getLogger(SessionLockService.class);
    private static final String KEY_PREFIX = "payroll:chat:lock:";
    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Set<Long> local = ConcurrentHashMap.newKeySet();

    public SessionLockService(StringRedisTemplate redis, int ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    /** 无 Redis 时的进程内实现，供测试与单机部署使用。 */
    public static SessionLockService disabled() {
        return new SessionLockService(null, 15);
    }

    /** 成功返回持有令牌；已被其它实例占用时返回空。 */
    public Optional<String> acquire(long sessionId) {
        String token = UUID.randomUUID().toString();
        if (redis != null) {
            try {
                Boolean acquired =
                        redis.opsForValue().setIfAbsent(KEY_PREFIX + sessionId, token, ttl);
                return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
            } catch (RuntimeException e) {
                log.debug("Redis 会话锁不可用，回退进程内锁：{}", e.getMessage());
            }
        }
        return local.add(sessionId) ? Optional.of(token) : Optional.empty();
    }

    /** 仅释放自己持有的锁，避免误删其它实例重建的锁。 */
    public void release(long sessionId, String token) {
        if (token == null) {
            return;
        }
        local.remove(sessionId);
        if (redis != null) {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + sessionId), token);
            } catch (RuntimeException e) {
                log.debug("释放 Redis 会话锁失败，等待 TTL 过期：{}", e.getMessage());
            }
        }
    }
}
