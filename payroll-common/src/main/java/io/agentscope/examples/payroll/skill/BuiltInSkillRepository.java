package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 代码内置角色技能，只读；用于 Nacos 不可用或配置缺失时兜底。 */
public final class BuiltInSkillRepository implements AgentSkillRepository, PayrollSkillCatalog {

    public static final String SOURCE = "builtin";

    private static final Map<String, AgentSkill> SKILLS = RoleSkills.all();
    /** 供 Nacos 仓库与组合仓库直接复用内置 manifest，避免再次构造。 */
    static final Map<String, SkillManifest> MANIFESTS;

    static {
        Map<String, SkillManifest> manifests = new LinkedHashMap<>();
        SKILLS.forEach((id, skill) -> manifests.put(id, SkillManifests.fromSkill(skill)));
        // 保持 RoleSkills 的声明顺序：目录顺序进入系统提示词与测试断言。
        MANIFESTS = Collections.unmodifiableMap(manifests);
    }

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

    @Override
    public List<SkillManifest> listManifests() {
        long started = System.nanoTime();
        try {
            return List.copyOf(MANIFESTS.values());
        } finally {
            SkillMetrics.catalogBuilt(SOURCE, System.nanoTime() - started);
        }
    }

    @Override
    public List<AgentSkill> getSkills(Collection<String> ids) {
        List<AgentSkill> skills = new ArrayList<>(ids == null ? 0 : ids.size());
        if (ids != null) {
            for (String id : ids) {
                AgentSkill skill = SKILLS.get(id);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        }
        return skills;
    }

    @Override
    public Optional<SkillManifest> manifest(String id) {
        return Optional.ofNullable(id == null ? null : MANIFESTS.get(id));
    }

    @Override
    public Map<String, String> nameById() {
        return MANIFESTS.entrySet().stream()
                .collect(
                        Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> entry.getValue().name()));
    }
}
