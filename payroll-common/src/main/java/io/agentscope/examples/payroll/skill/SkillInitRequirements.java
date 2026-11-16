package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 判断技能是否声明初始化数据，并解析出 setup 脚本路径。
 *
 * <p>frontmatter {@code setup: sh setup.sh} 优先；未声明但包含 {@code setup.sh} 时按默认
 * 脚本处理，与导入解析器的规则一致。解析只认 {@code setup.sh} 与 {@code scripts/**}，
 * 且要求文件确实在包内。
 */
public final class SkillInitRequirements {

    private static final Set<String> INTERPRETERS =
            Set.of("sh", "bash", "python", "python3", "node", "/bin/sh", "/bin/bash");
    private static final String SCRIPTS_PREFIX = "scripts/";

    private SkillInitRequirements() {
    }

    public static boolean requiresData(AgentSkill skill) {
        return setupPath(skill) != null;
    }

    /** 返回包内 setup 脚本路径；技能未声明初始化时返回 null。 */
    public static String setupPath(AgentSkill skill) {
        if (skill == null) {
            return null;
        }
        Object raw = skill.getMetadataValue("setup");
        return setupPath(
                raw == null ? null : String.valueOf(raw),
                path -> skill.getResource(path) != null);
    }

    /** 管理台按文件清单解析 setup 路径；{@code availableFiles} 为包内相对路径。 */
    public static String setupPath(String declaredCommand, Set<String> availableFiles) {
        return setupPath(
                declaredCommand,
                path -> availableFiles != null && availableFiles.contains(path));
    }

    private static String setupPath(String declaredCommand, Predicate<String> exists) {
        String command = declaredCommand == null ? "" : declaredCommand.trim();
        if (!command.isEmpty()) {
            for (String token : command.split("\\s+")) {
                if (token.isBlank() || token.startsWith("-") || INTERPRETERS.contains(token)) {
                    continue;
                }
                String target = token.startsWith("./") ? token.substring(2) : token;
                boolean allowed =
                        SkillFilePackage.SETUP_SH.equals(target)
                                || target.startsWith(SCRIPTS_PREFIX);
                return allowed && exists.test(target) ? target : null;
            }
            return null;
        }
        return exists.test(SkillFilePackage.SETUP_SH) ? SkillFilePackage.SETUP_SH : null;
    }
}
