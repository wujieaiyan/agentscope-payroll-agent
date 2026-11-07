package io.agentscope.examples.payroll.skill;

import java.time.Duration;
import java.util.Locale;

/** Nacos 技能源配置。设置 {@code PAYROLL_NACOS_SERVER} 即启用，也可用 {@code PAYROLL_NACOS_ENABLED} 显式覆盖。 */
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
        server = server == null ? "" : server.trim().replaceAll("/+$", "");
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
        String server = env("PAYROLL_NACOS_SERVER", "");
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
