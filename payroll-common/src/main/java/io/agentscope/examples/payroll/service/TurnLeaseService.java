package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 轮次租约：同一会话同一时刻只允许一轮回答，节点崩溃后 30 秒内可被接管。
 *
 * <p>令牌形如 {@code turnId:fencing}，续约与释放都校验令牌，旧持有者无法续租或误删新锁。
 * Redis 不可用时退化为进程内租约，仅保证单实例内互斥。
 */
public final class TurnLeaseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseService.class);
    private static final String KEY_PREFIX = "payroll:chat:turn:";
    private static final RedisScript<Long> RENEW_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
                    Long.class);
    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration heartbeat;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, String> tracked = new ConcurrentHashMap<>();
    private final Map<Long, LocalLease> local = new ConcurrentHashMap<>();

    public TurnLeaseService(StringRedisTemplate redis, int ttlSeconds, int heartbeatSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(Math.max(10, ttlSeconds));
        this.heartbeat =
                Duration.ofSeconds(Math.max(2, Math.min(heartbeatSeconds, ttlSeconds / 2)));
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "turn-lease-heartbeat");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.scheduler.scheduleWithFixedDelay(
                this::renewAll,
                this.heartbeat.toMillis(),
                this.heartbeat.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 无 Redis 时的进程内实现，供测试与单机部署使用。 */
    public static TurnLeaseService disabled() {
        return new TurnLeaseService(null, 30, 10);
    }

    /** 获取会话租约；已被其它实例占用时返回空。 */
    public Optional<String> acquire(long sessionId, String turnId, long fencingToken) {
        String token = turnId + ":" + fencingToken;
        if (redis != null) {
            try {
                Boolean acquired =
                        redis.opsForValue()
                                .setIfAbsent(KEY_PREFIX + sessionId, token, ttl);
                if (Boolean.TRUE.equals(acquired)) {
                    return Optional.of(token);
                }
                return Optional.empty();
            } catch (RuntimeException e) {
                log.warn("[轮次] Redis 租约不可用，回退进程内租约：{}", e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        LocalLease existing = local.get(sessionId);
        if (existing != null && existing.expiresAt() > now) {
            return Optional.empty();
        }
        local.put(sessionId, new LocalLease(token, now + ttl.toMillis()));
        return Optional.of(token);
    }

    /**
     * 接管已有租约：Web 提交后由 runtime 继续续约，令牌不变。
     * 令牌不匹配或已过期时返回 false。
     */
    public boolean takeover(long sessionId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (redis != null) {
            try {
                String current = redis.opsForValue().get(KEY_PREFIX + sessionId);
                if (!token.equals(current)) {
                    return false;
                }
                track(sessionId, token);
                return true;
            } catch (RuntimeException e) {
                log.warn("[轮次] 接管租约失败：{}", e.getMessage());
                return false;
            }
        }
        LocalLease existing = local.get(sessionId);
        if (existing == null || existing.expiresAt() <= System.currentTimeMillis()) {
            return false;
        }
        if (!existing.token().equals(token)) {
            return false;
        }
        existing.touch(System.currentTimeMillis() + ttl.toMillis());
        track(sessionId, token);
        return true;
    }

    /** 开始为本节点持有的租约续约。 */
    public void track(long sessionId, String token) {
        tracked.put(sessionId, token);
    }

    /** 停止续约（交棒给其它节点）。 */
    public void stopTracking(long sessionId) {
        tracked.remove(sessionId);
    }

    public boolean owns(long sessionId, String token) {
        if (token == null) {
            return false;
        }
        if (redis != null) {
            try {
                return token.equals(redis.opsForValue().get(KEY_PREFIX + sessionId));
            } catch (RuntimeException e) {
                log.debug("[轮次] 校验租约失败：{}", e.getMessage());
            }
        }
        LocalLease existing = local.get(sessionId);
        return existing != null
                && existing.token().equals(token)
                && existing.expiresAt() > System.currentTimeMillis();
    }

    public void release(long sessionId, String token) {
        if (token == null) {
            return;
        }
        String current = tracked.get(sessionId);
        if (token.equals(current)) {
            tracked.remove(sessionId);
        }
        local.remove(sessionId);
        if (redis != null) {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + sessionId), token);
            } catch (RuntimeException e) {
                log.debug("[轮次] 释放租约失败，等待 TTL 过期：{}", e.getMessage());
            }
        }
    }

    /** 清理进程退出时遗留的本地租约。 */
    public void releaseAll() {
        Set<Long> sessions = Set.copyOf(tracked.keySet());
        for (Long sessionId : sessions) {
            release(sessionId, tracked.get(sessionId));
        }
        local.clear();
    }

    private void renewAll() {
        if (tracked.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, String> entry : tracked.entrySet()) {
            long sessionId = entry.getKey();
            String token = entry.getValue();
            if (redis != null) {
                try {
                    redis.execute(
                            RENEW_SCRIPT,
                            List.of(KEY_PREFIX + sessionId),
                            token,
                            String.valueOf(ttl.toMillis()));
                } catch (RuntimeException e) {
                    log.debug("[轮次] 续约失败：sessionId={}，原因={}", sessionId, e.getMessage());
                }
                continue;
            }
            LocalLease lease = local.get(sessionId);
            if (lease != null && lease.token().equals(token)) {
                lease.touch(System.currentTimeMillis() + ttl.toMillis());
            }
        }
    }

    @Override
    public void close() {
        releaseAll();
        scheduler.shutdownNow();
    }

    /** 测试用：生成唯一轮次号。 */
    public static String newTurnId() {
        return UUID.randomUUID().toString();
    }

    private static final class LocalLease {

        private final String token;
        private volatile long expiresAt;

        private LocalLease(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        private String token() {
            return token;
        }

        private long expiresAt() {
            return expiresAt;
        }

        private void touch(long value) {
            expiresAt = value;
        }
    }
}
