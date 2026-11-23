package io.agentscope.examples.payroll.memory;

import java.time.Duration;

/** 向量记忆的启动配置，集中传参避免构造函数膨胀。 */
public record VectorMemorySettings(
        boolean enabled,
        String esUrl,
        String esUsername,
        String esPassword,
        String index,
        String embeddingBaseUrl,
        String embeddingModel,
        int embeddingDim,
        Duration embedTimeout,
        int topK,
        double minScore,
        int maxInjectChars,
        boolean asyncIndex,
        boolean crossSession,
        int recallBudgetMs,
        int breakerFailures,
        long breakerWindowSeconds,
        long breakerOpenSeconds) {

    /** 兼容旧调用：默认只召回当前会话，预算与熔断取默认值。 */
    public VectorMemorySettings(
            boolean enabled,
            String esUrl,
            String esUsername,
            String esPassword,
            String index,
            String embeddingBaseUrl,
            String embeddingModel,
            int embeddingDim,
            Duration embedTimeout,
            int topK,
            double minScore,
            int maxInjectChars,
            boolean asyncIndex,
            boolean crossSession) {
        this(
                enabled,
                esUrl,
                esUsername,
                esPassword,
                index,
                embeddingBaseUrl,
                embeddingModel,
                embeddingDim,
                embedTimeout,
                topK,
                minScore,
                maxInjectChars,
                asyncIndex,
                crossSession,
                600,
                3,
                60L,
                60L);
    }

    public VectorMemorySettings(
            boolean enabled,
            String esUrl,
            String esUsername,
            String esPassword,
            String index,
            String embeddingBaseUrl,
            String embeddingModel,
            int embeddingDim,
            Duration embedTimeout,
            int topK,
            double minScore,
            int maxInjectChars,
            boolean asyncIndex) {
        this(
                enabled,
                esUrl,
                esUsername,
                esPassword,
                index,
                embeddingBaseUrl,
                embeddingModel,
                embeddingDim,
                embedTimeout,
                topK,
                minScore,
                maxInjectChars,
                asyncIndex,
                false);
    }

    public VectorMemorySettings {
        topK = Math.max(1, topK);
        minScore = Math.max(0, Math.min(1, minScore));
        maxInjectChars = Math.max(200, maxInjectChars);
        embedTimeout = embedTimeout == null ? Duration.ofSeconds(3) : embedTimeout;
        recallBudgetMs = Math.max(1, recallBudgetMs);
        breakerFailures = Math.max(1, breakerFailures);
        breakerWindowSeconds = Math.max(1L, breakerWindowSeconds);
        breakerOpenSeconds = Math.max(1L, breakerOpenSeconds);
    }
}
