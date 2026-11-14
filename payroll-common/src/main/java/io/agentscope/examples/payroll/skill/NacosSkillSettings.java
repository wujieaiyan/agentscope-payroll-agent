package io.agentscope.examples.payroll.skill;

import java.time.Duration;
import java.util.Locale;

/**
 * Nacos 技能源配置。默认连接本机 {@code http://127.0.0.1:8848}；可用
 * {@code PAYROLL_NACOS_SERVER} 覆盖，或以 {@code PAYROLL_NACOS_ENABLED=false} 关闭。
 * 服务地址可带 {@code /nacos} 上下文路径，客户端会统一去掉后再拼接 v1 API。
 */
public record NacosSkillSettings(
        boolean enabled,
        String server,
        String group,
        String namespace,
        String username,
        String password,
        Duration timeout,
        Duration ttl) {

    public static final String DEFAULT_GROUP = "payroll-agent";

    public NacosSkillSettings {
        server = normalizeServer(server);
        group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
        namespace = namespace == null ? "" : namespace.trim();
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        ttl = ttl == null ? Duration.ofSeconds(60) : ttl;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Nacos 超时时间必须大于 0");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Nacos 技能缓存时间必须大于 0");
        }
    }

    public static NacosSkillSettings fromEnvironment() {
        String server = env("PAYROLL_NACOS_SERVER", "http://127.0.0.1:8848");
        boolean enabled = Boolean.parseBoolean(env("PAYROLL_NACOS_ENABLED", server.isBlank() ? "false" : "true"));
        return new NacosSkillSettings(
                enabled && !server.isBlank(),
                server,
                env("PAYROLL_NACOS_GROUP", DEFAULT_GROUP),
                env("PAYROLL_NACOS_NAMESPACE", ""),
                env("PAYROLL_NACOS_USERNAME", ""),
                env("PAYROLL_NACOS_PASSWORD", ""),
                Duration.ofMillis(longEnv("PAYROLL_NACOS_TIMEOUT_MS", 2000)),
                Duration.ofSeconds(longEnv("PAYROLL_NACOS_SKILL_TTL_SECONDS", 60)));
    }

    public String location() {
        return server + "/" + group + (namespace.isBlank() ? "" : "?namespace=" + namespace);
    }

    private static String normalizeServer(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("/+$", "");
        if (normalized.length() >= 6
                && normalized.regionMatches(true, normalized.length() - 6, "/nacos", 0, 6)) {
            normalized = normalized.substring(0, normalized.length() - 6).replaceAll("/+$", "");
        }
        return normalized;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数，当前值：" + value);
        }
    }
}
