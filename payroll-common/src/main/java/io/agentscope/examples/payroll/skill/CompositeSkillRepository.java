package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按顺序合并多个技能仓库，同名技能以靠前仓库为准。
 *
 * <p>典型用法是 {@code [Nacos, 内置]}：Nacos 发布过的技能覆盖内置版本，
 * 未发布或读取失败的技能自动回退内置版本。目录与批量取正文都按 id 首见优先合并，
 * 列表阶段不逐条拉正文。
 */
public final class CompositeSkillRepository
        implements AgentSkillRepository, PayrollSkillCatalog {

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
        Set<String> merged = new LinkedHashSet<>();
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                catalog.listManifests().forEach(manifest -> merged.add(manifest.id()));
            } else {
                merged.addAll(source.getAllSkillNames());
            }
        }
        return List.copyOf(merged);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return getSkills(getAllSkillNames());
    }

    @Override
    public List<SkillManifest> listManifests() {
        long started = System.nanoTime();
        try {
            Map<String, SkillManifest> merged = new LinkedHashMap<>();
            for (AgentSkillRepository source : sources) {
                if (source instanceof PayrollSkillCatalog catalog) {
                    catalog.listManifests()
                            .forEach(manifest -> merged.putIfAbsent(manifest.id(), manifest));
                    continue;
                }
                for (String name : source.getAllSkillNames()) {
                    AgentSkill skill = source.getSkill(name);
                    if (skill != null) {
                        merged.putIfAbsent(name, SkillManifests.fromSkill(skill));
                    }
                }
            }
            return List.copyOf(merged.values());
        } finally {
            SkillMetrics.catalogBuilt("composite", System.nanoTime() - started);
        }
    }

    @Override
    public List<AgentSkill> getSkills(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, AgentSkill> found = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(ids);
        for (AgentSkillRepository source : sources) {
            if (remaining.isEmpty()) {
                break;
            }
            List<AgentSkill> batch;
            if (source instanceof PayrollSkillCatalog catalog) {
                batch = catalog.getSkills(remaining);
            } else {
                List<String> pending = new ArrayList<>(remaining);
                batch = new ArrayList<>(pending.size());
                for (String id : pending) {
                    AgentSkill skill = source.getSkill(id);
                    if (skill != null) {
                        batch.add(skill);
                    }
                }
            }
            for (AgentSkill skill : batch) {
                if (skill != null && remaining.remove(skill.getName())) {
                    found.put(skill.getName(), skill);
                }
            }
        }
        Set<String> ordered = new LinkedHashSet<>(ids);
        List<AgentSkill> skills = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            AgentSkill skill = found.get(id);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    @Override
    public Optional<SkillManifest> manifest(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                Optional<SkillManifest> manifest = catalog.manifest(id);
                if (manifest.isPresent()) {
                    return manifest;
                }
                continue;
            }
            AgentSkill skill = source.getSkill(id);
            if (skill != null) {
                return Optional.of(SkillManifests.fromSkill(skill));
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<String, String> nameById() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                catalog.nameById().forEach(merged::putIfAbsent);
                continue;
            }
            for (String name : source.getAllSkillNames()) {
                merged.putIfAbsent(name, name);
            }
        }
        return Collections.unmodifiableMap(merged);
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
