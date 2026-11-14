package io.agentscope.examples.payroll.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内按 key 缓存智能体运行时。
 *
 * <p>不同智能体定义各占一个条目；定义变化由调用方体现为 key 变化。缓存条目可能在其它会话
 * 仍在使用，因此只有显式 {@link #clear()}（测试/应用关闭）或长时间空闲才关闭实例，会话重置
 * 不得关闭。
 */
final class AgentCache {

    private static final Logger log = LoggerFactory.getLogger(AgentCache.class);
    private static final int DEFAULT_MAX_ENTRIES = 64;
    private static final int DEFAULT_IDLE_MINUTES = 30;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long idleMillis;
    private final long pressureIdleMillis;

    AgentCache() {
        this(
                envInt("PAYROLL_AGENT_CACHE_MAX", DEFAULT_MAX_ENTRIES),
                Duration.ofMinutes(
                        Math.max(1, envInt("PAYROLL_AGENT_CACHE_IDLE_MINUTES", DEFAULT_IDLE_MINUTES))));
    }

    AgentCache(int maxEntries, Duration idleTtl) {
        this.maxEntries = Math.max(1, maxEntries);
        this.idleMillis = idleTtl == null ? Long.MAX_VALUE : Math.max(0L, idleTtl.toMillis());
        this.pressureIdleMillis =
                idleMillis == Long.MAX_VALUE
                        ? Long.MAX_VALUE
                        : Math.min(idleMillis, Duration.ofMinutes(15).toMillis());
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, Supplier<T> factory) {
        long now = System.currentTimeMillis();
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(factory.get(), now));
        entry.lastUsed = now;
        evictIdle(now);
        return (T) entry.value;
    }

    int size() {
        return entries.size();
    }

    void clear() {
        Map<String, Entry> snapshot = new LinkedHashMap<>(entries);
        entries.clear();
        snapshot.values().forEach(entry -> close(entry.value));
    }

    /**
     * 空闲条目直接淘汰；超过上限时允许提前淘汰空闲超过 15 分钟的条目，仍在执行的长对话不会
     * 被关闭（单轮 SSE 最长 10 分钟）。
     */
    private void evictIdle(long now) {
        if (cacheIdleDisabled()) {
            return;
        }
        boolean pressure = entries.size() > maxEntries;
        List<Map.Entry<String, Entry>> snapshot = new ArrayList<>(entries.entrySet());
        snapshot.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsed));
        for (Map.Entry<String, Entry> entry : snapshot) {
            long idleFor = now - entry.getValue().lastUsed;
            boolean expired = idleFor > idleMillis;
            boolean pressureExpired = pressure && idleFor > pressureIdleMillis;
            if (!expired && !pressureExpired) {
                continue;
            }
            if (entries.remove(entry.getKey(), entry.getValue())) {
                close(entry.getValue().value);
            }
        }
    }

    private boolean cacheIdleDisabled() {
        return idleMillis == Long.MAX_VALUE;
    }

    private static void close(Object value) {
        if (value instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("[Agent] 关闭缓存实例失败：{}", e.getMessage());
            }
        }
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Entry {

        private final Object value;
        private volatile long lastUsed;

        private Entry(Object value, long lastUsed) {
            this.value = value;
            this.lastUsed = lastUsed;
        }
    }
}
