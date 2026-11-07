package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 组装角色技能仓库：Nacos 优先，缺失时回退代码内置版本。 */
public final class SkillRepositories {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositories.class);

    private SkillRepositories() {
    }

    public static AgentSkillRepository create() {
        NacosSkillSettings settings = NacosSkillSettings.fromEnvironment();
        if (!settings.enabled()) {
            log.info("[Skill] Nacos 未启用，角色提示词使用代码内置版本（设置 PAYROLL_NACOS_SERVER 可外置）");
            return new BuiltInSkillRepository();
        }
        log.info(
                "[Skill] 角色提示词优先读取 Nacos {}（group={}，TTL={}s），缺失时回退代码内置版本",
                settings.server(),
                settings.group(),
                settings.ttl().toSeconds());
        return new CompositeSkillRepository(
                List.of(new NacosSkillRepository(settings), new BuiltInSkillRepository()));
    }

    /**
     * 动态技能按完整 skillId（{@code <name>_<source>}）过滤。这里同时放行 Nacos 与内置两个来源，
     * 使运行期 Nacos 恢复后无需重启即可切换到外置技能。
     */
    public static SkillFilter filterFor(String skillId) {
        return SkillFilter.only(
                skillId + "_" + NacosSkillRepository.SOURCE,
                skillId + "_" + BuiltInSkillRepository.SOURCE);
    }

    /**
     * 返回实际生效的技能。AgentScope 按 {@link AgentSkill#getName()}（不含来源后缀）激活
     * {@code activateOnSkill} 工具组，因此调用方必须从技能对象取名称，不能拼完整 skillId。
     */
    public static AgentSkill activeSkill(AgentSkillRepository repository, String skillId) {
        AgentSkill skill = repository.getSkill(skillId);
        return skill == null ? RoleSkills.skill(skillId) : skill;
    }

    /** 返回实际生效的技能 ID，仅用于启动日志。 */
    public static String describe(AgentSkillRepository repository, String skillId) {
        AgentSkill skill = repository.getSkill(skillId);
        if (skill == null) {
            return skillId + "(缺失)";
        }
        return skillId + "(" + skill.getSource() + ")";
    }
}
