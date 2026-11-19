package io.agentscope.examples.payroll.model;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.support.ModelEnv;
import java.util.Optional;

/** 环境变量兼容映射：把 {@code PAYROLL_MODEL} / {@code PAYROLL_DEV_*} 映射为只读连接。 */
public final class ModelConnections {

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String ENV_CONTROL_ID = "env-control";
    public static final String ENV_CODING_ID = "env-coding";

    public static final int DEFAULT_NUM_CTX = 8_192;
    public static final int DEFAULT_NUM_PREDICT = 1_024;
    public static final double DEFAULT_TEMPERATURE = 0.1D;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private ModelConnections() {}

    /** 控制面环境变量连接；始终存在（未配置时取内置默认模型）。 */
    public static ModelConnection envControl() {
        return fromModelId(
                ENV_CONTROL_ID,
                "环境变量（控制面）",
                ModelEnv.modelId(),
                env("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL),
                intEnv("PAYROLL_OLLAMA_NUM_CTX", DEFAULT_NUM_CTX),
                intEnv("PAYROLL_OLLAMA_NUM_PREDICT", DEFAULT_NUM_PREDICT),
                doubleEnv("PAYROLL_OLLAMA_TEMPERATURE", DEFAULT_TEMPERATURE),
                DEFAULT_TIMEOUT_SECONDS);
    }

    /** 编码面环境变量连接；未设置 {@code PAYROLL_DEV_MODEL} 时为空。 */
    public static Optional<ModelConnection> envCoding() {
        io.agentscope.examples.payroll.devteam.DevModelSettings settings =
                io.agentscope.examples.payroll.devteam.DevModelSettings.fromEnvironment();
        if (!settings.configured()) {
            return Optional.empty();
        }
        return Optional.of(
                fromModelId(
                        ENV_CODING_ID,
                        "环境变量（编码面）",
                        settings.modelId(),
                        settings.baseUrl(),
                        settings.numCtx(),
                        settings.numPredict(),
                        settings.temperature(),
                        settings.timeoutSeconds()));
    }

    public static boolean envCodingConfigured() {
        return io.agentscope.examples.payroll.devteam.DevModelSettings.fromEnvironment()
                .configured();
    }

    /** 解析 {@code ollama:<模型>} / {@code dashscope:<模型>} / 裸模型名。 */
    public static ModelConnection fromModelId(
            String id,
            String name,
            String modelId,
            String baseUrl,
            int numCtx,
            int numPredict,
            double temperature,
            int timeoutSeconds) {
        String value = modelId == null ? "" : modelId.trim();
        if (value.isBlank()) {
            throw new PayrollException("模型标识不能为空");
        }
        String provider;
        String model;
        if (value.startsWith("ollama:")) {
            provider = ModelConnection.PROVIDER_OLLAMA;
            model = value.substring("ollama:".length()).trim();
            if (model.isBlank()) {
                throw new PayrollException(
                        "模型标识格式错误，应为 ollama:<模型名>，例如 ollama:qwen2.5:3b-instruct");
            }
        } else if (value.startsWith("dashscope:")) {
            provider = ModelConnection.PROVIDER_DASHSCOPE;
            model = value.substring("dashscope:".length()).trim();
        } else {
            provider = ModelConnection.PROVIDER_DASHSCOPE;
            model = value;
        }
        return new ModelConnection(
                id,
                name,
                provider,
                ModelConnection.PROVIDER_OLLAMA.equals(provider) ? baseUrl : "",
                model,
                ModelConnection.PROVIDER_DASHSCOPE.equals(provider) ? "DASHSCOPE_API_KEY" : "",
                "",
                numCtx,
                numPredict,
                temperature,
                timeoutSeconds,
                java.util.List.of(ModelConnection.CAP_TOOLS, ModelConnection.CAP_STREAM),
                Boolean.TRUE,
                null,
                "env",
                ModelConnection.ORIGIN_ENV);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new PayrollException(key + " 必须是整数，当前值：" + value);
        }
    }

    private static double doubleEnv(String key, double defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new PayrollException(key + " 必须是数字，当前值：" + value);
        }
    }
}
