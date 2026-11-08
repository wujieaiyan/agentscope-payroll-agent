package io.agentscope.examples.payroll.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内按 key 缓存智能体运行时。
 *
 * <p>不同智能体定义各占一个条目；定义变化由调用方体现为 key 变化。缓存条目可能在其它会话
 * 仍在使用，因此只有显式 {@link #clear()}（测试/应用关闭）才关闭实例，会话重置不得关闭。
 */
final class AgentCache {

    private static final Logger log = LoggerFactory.getLogger(AgentCache.class);

    private final Map<String, Object> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    <T> T get(String key, Supplier<T> factory) {
        return (T) entries.computeIfAbsent(key, ignored -> factory.get());
    }

    int size() {
        return entries.size();
    }

    void clear() {
        Map<String, Object> snapshot = new LinkedHashMap<>(entries);
        entries.clear();
        for (Object value : snapshot.values()) {
            if (value instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("[Agent] 关闭缓存实例失败：{}", e.getMessage());
                }
            }
        }
    }
}
