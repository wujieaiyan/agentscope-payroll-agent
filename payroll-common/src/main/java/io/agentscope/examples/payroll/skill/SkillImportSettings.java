package io.agentscope.examples.payroll.skill;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 技能项目导入限制：Git 主机白名单与 ZIP 上传大小上限。 */
public record SkillImportSettings(Set<String> allowedHosts, long maxZipBytes) {

    public static final String DEFAULT_HOSTS = "github.com,gitlab.com,gitee.com";
    public static final long DEFAULT_MAX_ZIP_BYTES = 20L * 1024 * 1024;

    public SkillImportSettings {
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        maxZipBytes = maxZipBytes <= 0 ? DEFAULT_MAX_ZIP_BYTES : maxZipBytes;
    }

    public static SkillImportSettings defaults() {
        return new SkillImportSettings(parseHosts(DEFAULT_HOSTS), DEFAULT_MAX_ZIP_BYTES);
    }

    public static SkillImportSettings fromEnvironment() {
        String hosts = System.getenv("PAYROLL_SKILL_IMPORT_HOSTS");
        return new SkillImportSettings(
                parseHosts(hosts == null || hosts.isBlank() ? DEFAULT_HOSTS : hosts),
                DEFAULT_MAX_ZIP_BYTES);
    }

    public boolean allows(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseHosts(String raw) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isBlank()) {
                hosts.add(host);
            }
        }
        return Set.copyOf(hosts);
    }
}
