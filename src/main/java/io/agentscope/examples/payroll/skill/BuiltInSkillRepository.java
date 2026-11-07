package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.List;
import java.util.Map;

/** 代码内置角色技能，只读；用于 Nacos 不可用或配置缺失时兜底。 */
public final class BuiltInSkillRepository implements AgentSkillRepository {

    public static final String SOURCE = "builtin";

    private static final Map<String, AgentSkill> SKILLS = RoleSkills.all();

    @Override
    public AgentSkill getSkill(String name) {
        return SKILLS.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(SKILLS.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return List.copyOf(SKILLS.values());
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return SKILLS.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(SOURCE, "代码内置", false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void close() {
    }
}
