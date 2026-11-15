package io.agentscope.examples.payroll.skill;

/**
 * 技能脚本执行配置：总开关、沙箱地址与 HMAC 令牌、单次超时。
 *
 * <p>三者同时满足才注册 {@code skill_run}：总开关为真、沙箱地址非空、令牌非空。
 */
public record SkillExecutionSettings(
        boolean enabled, String sandboxUrl, String sandboxToken, int timeoutMs) {

    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int MAX_TIMEOUT_MS = 10_000;

    public SkillExecutionSettings {
        sandboxUrl = sandboxUrl == null ? "" : sandboxUrl.trim();
        sandboxToken = sandboxToken == null ? "" : sandboxToken.trim();
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
    }

    public static SkillExecutionSettings fromEnvironment() {
        return new SkillExecutionSettings(
                !"false".equalsIgnoreCase(env("PAYROLL_SKILL_EXEC_ENABLED", "false").trim()),
                env("PAYROLL_SANDBOX_URL", ""),
                env("PAYROLL_SANDBOX_TOKEN", ""),
                intEnv("PAYROLL_SANDBOX_TIMEOUT_MS", DEFAULT_TIMEOUT_MS));
    }

    public static SkillExecutionSettings disabled() {
        return new SkillExecutionSettings(false, "", "", DEFAULT_TIMEOUT_MS);
    }

    public boolean configured() {
        return enabled && !sandboxUrl.isBlank() && !sandboxToken.isBlank();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
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
}
