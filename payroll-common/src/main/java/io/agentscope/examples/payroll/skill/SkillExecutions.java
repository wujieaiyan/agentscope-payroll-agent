package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Map;

/**
 * 技能级执行开关：frontmatter {@code execution.enabled: true} 才允许 {@code skill_run}。
 *
 * <p>YAML 解析器可能把嵌套结构变成 {@code execution -> {enabled: true}}，也可能拍平成
 * {@code execution.enabled}，两种写法都要识别。
 */
public final class SkillExecutions {

    public static final String META_EXECUTION = "execution";
    public static final String META_ENABLED = "execution.enabled";

    private SkillExecutions() {
    }

    public static boolean executionEnabled(AgentSkill skill) {
        if (skill == null) {
            return false;
        }
        return truthy(skill.getMetadataValue(META_ENABLED))
                || nestedEnabled(skill.getMetadataValue(META_EXECUTION));
    }

    public static boolean executionEnabled(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return truthy(metadata.get(META_ENABLED)) || nestedEnabled(metadata.get(META_EXECUTION));
    }

    public static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static boolean nestedEnabled(Object value) {
        if (value instanceof Map<?, ?> map) {
            return truthy(map.get("enabled"));
        }
        return false;
    }
}
