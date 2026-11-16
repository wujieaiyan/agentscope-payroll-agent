package io.agentscope.examples.payroll.skill;

/**
 * 技能包伴生文件（{@code <id>.files.json}）的读取开关与配额。
 *
 * <p>关闭时正文照常加载，只是忽略文件包；这样回滚不需要清理 Nacos 配置。
 * v2 把单文件与总量上限调大到可容纳目录式技能项目，大数据仍应放技能工作区的
 * {@code data/}（见 docs/skill-project-package-design.md）。
 */
public record SkillFileSettings(
        boolean enabled, int maxFileBytes, int maxTotalBytes, int maxFiles) {

    public static final int DEFAULT_MAX_FILE_BYTES = 512 * 1024;
    public static final int DEFAULT_MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_MAX_FILES = 200;

    public SkillFileSettings {
        maxFileBytes = maxFileBytes <= 0 ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        maxTotalBytes = maxTotalBytes <= 0 ? DEFAULT_MAX_TOTAL_BYTES : maxTotalBytes;
        maxFiles = maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
        if (maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("技能单文件上限不能大于总量上限");
        }
    }

    public SkillFileSettings(boolean enabled, int maxFileBytes, int maxTotalBytes) {
        this(enabled, maxFileBytes, maxTotalBytes, DEFAULT_MAX_FILES);
    }

    public static SkillFileSettings fromEnvironment() {
        return new SkillFileSettings(
                !"false".equalsIgnoreCase(env("PAYROLL_SKILL_FILES_ENABLED", "true")),
                intEnv("PAYROLL_SKILL_FILE_MAX_BYTES", DEFAULT_MAX_FILE_BYTES),
                intEnv("PAYROLL_SKILL_FILES_MAX_BYTES", DEFAULT_MAX_TOTAL_BYTES),
                intEnv("PAYROLL_SKILL_FILES_MAX_FILES", DEFAULT_MAX_FILES));
    }

    public static SkillFileSettings defaults() {
        return new SkillFileSettings(
                true,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_TOTAL_BYTES,
                DEFAULT_MAX_FILES);
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
            throw new IllegalArgumentException(key + " 必须是整数，当前值：" + value);
        }
    }
}
