package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 可复用的模型接入配置；连接元数据存 Nacos，密钥只存引用。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ModelConnection(
        String id,
        String name,
        String provider,
        String baseUrl,
        String model,
        String apiKeyEnv,
        String credentialRef,
        Integer contextWindow,
        Integer maxTokens,
        Double temperature,
        Integer timeoutSeconds,
        List<String> capabilities,
        Boolean enabled,
        LocalDateTime updatedAt,
        String updatedBy,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String origin) {

    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_OPENAI = "openai-compatible";
    public static final Set<String> PROVIDERS =
            Set.of(PROVIDER_OLLAMA, PROVIDER_DASHSCOPE, PROVIDER_OPENAI);

    public static final String ORIGIN_STORED = "STORED";
    public static final String ORIGIN_ENV = "ENV";

    public static final String CAP_TOOLS = "tools";
    public static final String CAP_STREAM = "stream";

    public ModelConnection {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        model = model == null ? "" : model.trim();
        apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.trim();
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        updatedBy = updatedBy == null ? "" : updatedBy.trim();
        origin = origin == null || origin.isBlank() ? ORIGIN_STORED : origin;
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
    }

    public boolean ollama() {
        return PROVIDER_OLLAMA.equals(provider);
    }

    public boolean envDerived() {
        return ORIGIN_ENV.equalsIgnoreCase(origin);
    }

    public boolean requiresApiKey() {
        return !ollama();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public String credentialSource() {
        if (credentialRef != null && !credentialRef.isBlank()) {
            return "stored";
        }
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            return "env";
        }
        return ollama() ? "none" : "unset";
    }

    public boolean declares(String capability) {
        return capability != null
                && capabilities.stream().anyMatch(item -> capability.equalsIgnoreCase(item));
    }

    public int numCtx(int fallback) {
        return contextWindow == null || contextWindow <= 0 ? fallback : contextWindow;
    }

    public int numPredict(int fallback) {
        return maxTokens == null || maxTokens <= 0 ? fallback : maxTokens;
    }

    public double temperature(double fallback) {
        return temperature == null || temperature < 0D ? fallback : temperature;
    }

    public int timeoutSeconds(int fallback) {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? fallback : timeoutSeconds;
    }

    /** 模型实例缓存键：连接版本变化（任何影响推理的参数）都必须重建。 */
    public String versionKey() {
        return id
                + "|"
                + provider
                + "|"
                + baseUrl
                + "|"
                + model
                + "|"
                + (contextWindow == null ? "" : contextWindow)
                + "|"
                + (maxTokens == null ? "" : maxTokens)
                + "|"
                + (temperature == null ? "" : temperature)
                + "|"
                + (timeoutSeconds == null ? "" : timeoutSeconds)
                + "|"
                + String.join(",", capabilities);
    }

    /** 连接版本 + 凭证版本：密钥轮换后必须重建模型实例。 */
    public String versionKey(String credentialVersion) {
        return versionKey() + "|cred:" + (credentialVersion == null ? "" : credentialVersion);
    }

    /** 校验可保存的必填项；返回规范化后的连接。 */
    public ModelConnection validated() {
        if (id.isBlank() || !id.matches("[a-z0-9-]{1,64}")) {
            throw new PayrollException("连接 ID 只能包含小写字母、数字与短横线，且不超过 64 个字符");
        }
        if (name.isBlank() || name.length() > 60) {
            throw new PayrollException("连接名称不能为空，且不超过 60 个字符");
        }
        if (!PROVIDERS.contains(provider)) {
            throw new PayrollException("不支持的模型提供方：" + provider
                    + "（可选 ollama / dashscope / openai-compatible）");
        }
        if (model.isBlank()) {
            throw new PayrollException("模型名不能为空");
        }
        if (!apiKeyEnv.isBlank() && !credentialRef.isBlank()) {
            throw new PayrollException("apiKeyEnv 与 credentialRef 只能二选一");
        }
        if (!apiKeyEnv.isBlank() && !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new PayrollException("apiKeyEnv 必须是合法的环境变量名");
        }
        if (PROVIDER_OPENAI.equals(provider) && baseUrl.isBlank()) {
            throw new PayrollException("OpenAI 兼容连接必须填写 baseUrl");
        }
        String effectiveUrl =
                baseUrl.isBlank() && PROVIDER_OLLAMA.equals(provider)
                        ? ModelConnections.DEFAULT_OLLAMA_BASE_URL
                        : baseUrl;
        if (!effectiveUrl.isBlank()) {
            URI uri;
            try {
                uri = URI.create(effectiveUrl);
            } catch (IllegalArgumentException e) {
                throw new PayrollException("baseUrl 不是合法地址：" + effectiveUrl);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new PayrollException("baseUrl 只支持 http / https 协议");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new PayrollException("baseUrl 必须包含主机名：" + effectiveUrl);
            }
        }
        if (contextWindow != null && contextWindow <= 0) {
            throw new PayrollException("contextWindow 必须大于 0");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new PayrollException("maxTokens 必须大于 0");
        }
        if (temperature != null && (temperature < 0D || temperature > 2D)) {
            throw new PayrollException("temperature 必须在 0 到 2 之间");
        }
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            throw new PayrollException("timeoutSeconds 必须大于 0");
        }
        return new ModelConnection(
                id,
                name,
                provider,
                effectiveUrl.equals(baseUrl) ? baseUrl : effectiveUrl,
                model,
                apiKeyEnv,
                credentialRef,
                contextWindow,
                maxTokens,
                temperature,
                timeoutSeconds,
                capabilities.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(item -> item.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList(),
                Boolean.TRUE.equals(enabled),
                updatedAt,
                updatedBy,
                origin);
    }

    public ModelConnection withCredentialRef(String reference) {
        return new ModelConnection(
                id,
                name,
                provider,
                baseUrl,
                model,
                apiKeyEnv,
                reference,
                contextWindow,
                maxTokens,
                temperature,
                timeoutSeconds,
                capabilities,
                enabled,
                updatedAt,
                updatedBy,
                origin);
    }

    public ModelConnection withApiKeyEnv(String envName) {
        return new ModelConnection(
                id,
                name,
                provider,
                baseUrl,
                model,
                envName,
                credentialRef,
                contextWindow,
                maxTokens,
                temperature,
                timeoutSeconds,
                capabilities,
                enabled,
                updatedAt,
                updatedBy,
                origin);
    }

    public ModelConnection withOrigin(String value) {
        return new ModelConnection(
                id,
                name,
                provider,
                baseUrl,
                model,
                apiKeyEnv,
                credentialRef,
                contextWindow,
                maxTokens,
                temperature,
                timeoutSeconds,
                capabilities,
                enabled,
                updatedAt,
                updatedBy,
                value);
    }

    /** 仅覆盖显式提供的字段，用于智能体级模型覆盖。 */
    public ModelConnection overlay(AgentModelSettings override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        return new ModelConnection(
                id,
                name,
                provider,
                baseUrl,
                override.model() == null || override.model().isBlank() ? model : override.model(),
                apiKeyEnv,
                credentialRef,
                override.numCtx() == null ? contextWindow : override.numCtx(),
                override.numPredict() == null ? maxTokens : override.numPredict(),
                override.temperature() == null ? temperature : override.temperature(),
                override.timeoutSeconds() == null ? timeoutSeconds : override.timeoutSeconds(),
                capabilities,
                enabled,
                updatedAt,
                updatedBy,
                origin);
    }
}
