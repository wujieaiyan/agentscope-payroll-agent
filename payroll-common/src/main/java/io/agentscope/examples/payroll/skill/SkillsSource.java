package io.agentscope.examples.payroll.skill;

import java.util.Locale;

/**
 * 技能来源开关（规格 §11.1、§16）：单源，运行期不热切。
 *
 * <p>与 {@code payroll.definitions.source} 同构，但这里不引入 {@code both}/影子读：两条读路径的
 * 目录顺序与缓存语义不同，同时读会污染智能体缓存指纹（{@code skillFingerprint} 带 {@code source}），
 * 灰度靠 §11 的导入 + 对账 + 重启完成。
 */
public enum SkillsSource {
    NACOS("nacos"),
    DB(JdbcSkillStore.SOURCE_DB);

    /** Spring 键 {@code payroll.skills.source} 对应的环境变量。 */
    public static final String ENV = "PAYROLL_SKILLS_SOURCE";

    private final String value;

    SkillsSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SkillsSource parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || NACOS.value.equals(value)) {
            return NACOS;
        }
        if (DB.value.equals(value)) {
            return DB;
        }
        throw new IllegalArgumentException(
                "不支持的技能来源：" + raw + "（可选 " + NACOS.value + " / " + DB.value + "）");
    }

    /** 非 Spring 入口（命令行、测试）读环境变量取来源。 */
    public static SkillsSource fromEnvironment() {
        return parse(System.getenv(ENV));
    }
}
