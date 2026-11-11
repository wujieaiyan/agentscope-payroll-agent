package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 登录失败限流：同一账号连续失败达到阈值后锁定一段时间。
 *
 * <p>有 Redis 时多实例共享计数；Redis 不可用（或未启用 redis profile）时回退到进程内计数，
 * 保证登录链路不因缓存故障整体不可用。
 */
public final class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String KEY_PREFIX = "payroll:login:fail:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;
    private final Map<String, LocalState> local = new ConcurrentHashMap<>();

    public LoginRateLimiter(StringRedisTemplate redis, int maxFailures, int lockMinutes) {
        this.redis = redis;
        this.maxFailures = Math.max(1, maxFailures);
        this.window = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    /** 返回剩余锁定时长；未锁定时为空。 */
    public Optional<Duration> lockedFor(String username) {
        String key = normalize(username);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(KEY_PREFIX + key);
                if (value != null && Integer.parseInt(value) >= maxFailures) {
                    Long ttl = redis.getExpire(KEY_PREFIX + key, TimeUnit.SECONDS);
                    return Optional.of(
                            Duration.ofSeconds(ttl == null || ttl <= 0 ? window.toSeconds() : ttl));
                }
                return Optional.empty();
            } catch (RuntimeException e) {
                log.debug("Redis 登录限流不可用，回退进程内计数：{}", e.getMessage());
            }
        }
        return localLockedFor(key);
    }

    public void onFailure(String username) {
        String key = normalize(username);
        if (key.isEmpty()) {
            return;
        }
        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(KEY_PREFIX + key);
                if (count != null && count == 1L) {
                    redis.expire(KEY_PREFIX + key, window);
                }
                if (count != null && count >= maxFailures) {
                    redis.expire(KEY_PREFIX + key, window);
                }
                return;
            } catch (RuntimeException e) {
                log.debug("Redis 登录限流不可用，回退进程内计数：{}", e.getMessage());
            }
        }
        LocalState state = local.computeIfAbsent(key, ignored -> new LocalState());
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.windowStart == 0 || now - state.windowStart > window.toMillis()) {
                state.failures = 0;
                state.windowStart = now;
                state.lockUntil = 0;
            }
            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockUntil = now + window.toMillis();
            }
        }
    }
    public void onSuccess(String username) {
        String key = normalize(username);
        if (key.isEmpty()) {
            return;
        }
        local.remove(key);
        if (redis != null) {
            try {
                redis.delete(KEY_PREFIX + key);
            } catch (RuntimeException e) {
                log.debug("清理 Redis 登录失败计数失败：{}", e.getMessage());
            }
        }
    }

    private Optional<Duration> localLockedFor(String key) {
        LocalState state = local.get(key);
        if (state == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.lockUntil > now) {
                return Optional.of(Duration.ofMillis(state.lockUntil - now));
            }
            if (now - state.windowStart > window.toMillis()) {
                local.remove(key);
            }
            return Optional.empty();
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static final class LocalState {
        private int failures;
        private long windowStart;
        private long lockUntil;
    }
}
