package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按顺序合并多个技能仓库，同名技能以靠前仓库为准。
 *
 * <p>典型用法是 {@code [Nacos, 内置]}：Nacos 发布过的技能覆盖内置版本，
 * 未发布或读取失败的技能自动回退内置版本。
 */
public final class CompositeSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(CompositeSkillRepository.class);

    private final List<AgentSkillRepository> sources;

    public CompositeSkillRepository(List<AgentSkillRepository> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public AgentSkill getSkill(String name) {
        for (AgentSkillRepository source : sources) {
            AgentSkill skill = source.getSkill(name);
            if (skill != null) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(getAllSkills().stream().map(AgentSkill::getName).toList());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        Map<String, AgentSkill> merged = new LinkedHashMap<>();
        for (AgentSkillRepository source : sources) {
            for (AgentSkill skill : source.getAllSkills()) {
                merged.putIfAbsent(skill.getName(), skill);
            }
        }
        return List.copyOf(merged.values());
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
        return getSkill(name) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        String location =
                sources.stream()
                        .map(source -> source.getRepositoryInfo().getType())
                        .collect(Collectors.joining(" -> "));
        return new AgentSkillRepositoryInfo("composite", location, false);
    }

    @Override
    public String getSource() {
        return "composite";
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
        for (AgentSkillRepository source : sources) {
            try {
                source.close();
            } catch (RuntimeException e) {
                log.warn("[Skill] 关闭技能仓库 {} 失败：{}", source.getSource(), e.getMessage());
            }
        }
    }
}
