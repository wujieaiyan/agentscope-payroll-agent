package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.Optional;

/**
 * 编码面模型配置（A7）：控制面继续使用 {@code PAYROLL_MODEL}，编码面只认
 * {@code PAYROLL_DEV_MODEL}，两者不共用实例。
 *
 * <p>未配置或有效上下文低于 {@link #MIN_CONTEXT} 时编码能力关闭，开发域退回只读；
 * 禁止静默回落到控制面模型生成补丁。
 */
public record DevModelSettings(
        String modelId,
        String baseUrl,
        int numCtx,
        int numPredict,
        double temperature,
        int timeoutSeconds) {

    public static final int MIN_CONTEXT = 32_768;
    public static final int DEFAULT_NUM_CTX = 32_768;
    public static final int DEFAULT_NUM_PREDICT = 4_096;
    public static final double DEFAULT_TEMPERATURE = 0.2D;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    public static final String ENV_MODEL = "PAYROLL_DEV_MODEL";
    public static final String ENV_BASE_URL = "PAYROLL_DEV_OLLAMA_BASE_URL";
    public static final String ENV_NUM_CTX = "PAYROLL_DEV_NUM_CTX";
    public static final String ENV_NUM_PREDICT = "PAYROLL_DEV_NUM_PREDICT";
    public static final String ENV_TEMPERATURE = "PAYROLL_DEV_TEMPERATURE";
    public static final String ENV_TIMEOUT_SECONDS = "PAYROLL_DEV_TIMEOUT_SECONDS";

    public DevModelSettings {
        modelId = modelId == null ? "" : modelId.trim();
        baseUrl =
                baseUrl == null || baseUrl.isBlank()
                        ? DEFAULT_OLLAMA_BASE_URL
                        : baseUrl.trim();
        numCtx = numCtx <= 0 ? DEFAULT_NUM_CTX : numCtx;
        numPredict = numPredict <= 0 ? DEFAULT_NUM_PREDICT : numPredict;
        temperature = temperature < 0D ? DEFAULT_TEMPERATURE : temperature;
        timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    public static DevModelSettings fromEnvironment() {
        return new DevModelSettings(
                env(ENV_MODEL, ""),
                env(ENV_BASE_URL, env("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL)),
                intEnv(ENV_NUM_CTX, DEFAULT_NUM_CTX),
                intEnv(ENV_NUM_PREDICT, DEFAULT_NUM_PREDICT),
                doubleEnv(ENV_TEMPERATURE, DEFAULT_TEMPERATURE),
                intEnv(ENV_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS));
    }

    public boolean configured() {
        return !modelId.isBlank();
    }

    /** 编码能力可用：已配置且有效上下文达到 32k。 */
    public boolean writeCapable() {
        return configured() && numCtx >= MIN_CONTEXT;
    }

    public Optional<String> disabledReason() {
        if (!configured()) {
            return Optional.of("未设置 " + ENV_MODEL + "，编码能力关闭");
        }
        if (numCtx < MIN_CONTEXT) {
            return Optional.of(
                    ENV_NUM_CTX + "=" + numCtx + " 低于 " + MIN_CONTEXT + "，编码能力关闭");
        }
        return Optional.empty();
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
