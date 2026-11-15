package io.agentscope.examples.payroll.agent;

/**
 * 语义路由配置：独立于知识库检索的嵌入模型与索引，避免影响已入库的 KB 向量。
 *
 * <p>超时用于在线判定，失败一律 fail-open 交回 L2 模型路由；种子写入使用更长超时。
 */
public record SemanticRouteSettings(
        boolean enabled,
        double minScore,
        double margin,
        String index,
        int timeoutMs,
        int seedTimeoutSeconds,
        String esUrl,
        String esUsername,
        String esPassword,
        String embeddingBaseUrl,
        String embeddingModel,
        int embeddingDim) {

    public SemanticRouteSettings {
        minScore = minScore <= 0 || minScore >= 1 ? 0.50 : minScore;
        margin = margin <= 0 || margin >= 1 ? 0.05 : margin;
        index = index == null || index.isBlank() ? "payroll-route-intents" : index.trim();
        timeoutMs = timeoutMs <= 0 ? 150 : timeoutMs;
        seedTimeoutSeconds = seedTimeoutSeconds <= 0 ? 60 : seedTimeoutSeconds;
        esUrl = esUrl == null || esUrl.isBlank() ? "http://localhost:9200" : esUrl.trim();
        esUsername = esUsername == null ? "" : esUsername.trim();
        esPassword = esPassword == null ? "" : esPassword;
        embeddingBaseUrl =
                embeddingBaseUrl == null || embeddingBaseUrl.isBlank()
                        ? "http://localhost:11434"
                        : embeddingBaseUrl.trim();
        embeddingModel =
                embeddingModel == null || embeddingModel.isBlank()
                        ? "qwen3-embedding:0.6b"
                        : embeddingModel.trim();
        embeddingDim = embeddingDim <= 0 ? 1024 : embeddingDim;
    }

    public static SemanticRouteSettings disabled() {
        return new SemanticRouteSettings(
                false, 0.50, 0.05, "payroll-route-intents", 150, 60,
                "", "", "", "", "", 1024);
    }
}
