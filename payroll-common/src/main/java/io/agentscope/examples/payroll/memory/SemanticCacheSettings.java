package io.agentscope.examples.payroll.memory;

import java.time.Duration;

/** 语义缓存配置：连接参数与向量记忆相同，索引和阈值独立。 */
public record SemanticCacheSettings(
        boolean enabled,
        String esUrl,
        String esUsername,
        String esPassword,
        String index,
        String embeddingBaseUrl,
        String embeddingModel,
        int embeddingDim,
        Duration embedTimeout,
        double threshold,
        int ttlMinutes,
        int maxEntries) {

    public SemanticCacheSettings {
        embeddingDim = Math.max(1, embeddingDim);
        embedTimeout = embedTimeout == null ? Duration.ofSeconds(3) : embedTimeout;
        threshold = Math.max(0.5, Math.min(1.0, threshold));
        ttlMinutes = Math.max(1, ttlMinutes);
        maxEntries = Math.max(100, maxEntries);
    }
}
