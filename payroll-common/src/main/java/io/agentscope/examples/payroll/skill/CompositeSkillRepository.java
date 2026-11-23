package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按顺序合并多个技能仓库，同名技能以靠前仓库为准。
 *
 * <p>典型用法是 {@code [Nacos, 内置]}：Nacos 发布过的技能覆盖内置版本，
 * 未发布或读取失败的技能自动回退内置版本。目录与批量取正文都按 id 首见优先合并，
 * 列表阶段不逐条拉正文。
 *
 * <p>来源连不上（{@link PayrollUnavailableException}）时跳过该来源继续读下一个，并按最终供货方打
 * {@code skill.source.fallback.count}；库里的技能内容坏了仍照常抛出，不能伪装成「没有这个技能」。
 */
public final class CompositeSkillRepository
        implements AgentSkillRepository, PayrollSkillCatalog {

    /** 整表合并类读取无法指名「由哪个来源顶上」，降级计数统一打这个标签。 */
    private static final String MERGED = "composite";

    private static final String NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(CompositeSkillRepository.class);

    private final List<AgentSkillRepository> sources;

    public CompositeSkillRepository(List<AgentSkillRepository> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public AgentSkill getSkill(String name) {
        List<String> skipped = new ArrayList<>();
        for (AgentSkillRepository source : sources) {
            AgentSkill skill = read(source, skipped, () -> source.getSkill(name), null);
            if (skill != null) {
                countFallbacks(skipped, source.getSource());
                return skill;
            }
        }
        countFallbacks(skipped, NONE);
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> skipped = new ArrayList<>();
        Set<String> merged = new LinkedHashSet<>();
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                read(source, skipped, catalog::listManifests, List.<SkillManifest>of())
                        .forEach(manifest -> merged.add(manifest.id()));
            } else {
                read(source, skipped, source::getAllSkillNames, List.<String>of()).forEach(merged::add);
            }
        }
        countFallbacks(skipped, MERGED);
        return List.copyOf(merged);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return getSkills(getAllSkillNames());
    }

    @Override
    public List<SkillManifest> listManifests() {
        long started = System.nanoTime();
        List<String> skipped = new ArrayList<>();
        try {
            Map<String, SkillManifest> merged = new LinkedHashMap<>();
            for (AgentSkillRepository source : sources) {
                if (source instanceof PayrollSkillCatalog catalog) {
                    read(source, skipped, catalog::listManifests, List.<SkillManifest>of())
                            .forEach(manifest -> merged.putIfAbsent(manifest.id(), manifest));
                    continue;
                }
                for (String name :
                        read(source, skipped, source::getAllSkillNames, List.<String>of())) {
                    AgentSkill skill = read(source, skipped, () -> source.getSkill(name), null);
                    if (skill != null) {
                        merged.putIfAbsent(name, SkillManifests.fromSkill(skill));
                    }
                }
            }
            countFallbacks(skipped, MERGED);
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
        List<String> skipped = new ArrayList<>();
        Map<String, AgentSkill> found = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(ids);
        for (AgentSkillRepository source : sources) {
            if (remaining.isEmpty()) {
                break;
            }
            Set<String> pending = Set.copyOf(remaining);
            List<AgentSkill> batch;
            if (source instanceof PayrollSkillCatalog catalog) {
                batch =
                        read(
                                source,
                                skipped,
                                () -> catalog.getSkills(pending),
                                List.<AgentSkill>of());
            } else {
                List<AgentSkill> items = new ArrayList<>(pending.size());
                for (String id : pending) {
                    AgentSkill skill = read(source, skipped, () -> source.getSkill(id), null);
                    if (skill != null) {
                        items.add(skill);
                    }
                }
                batch = items;
            }
            for (AgentSkill skill : batch) {
                if (skill != null && remaining.remove(skill.getName())) {
                    found.put(skill.getName(), skill);
                }
            }
        }
        countFallbacks(skipped, MERGED);
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
        List<String> skipped = new ArrayList<>();
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                Optional<SkillManifest> manifest =
                        read(
                                source,
                                skipped,
                                () -> catalog.manifest(id),
                                Optional.<SkillManifest>empty());
                if (manifest.isPresent()) {
                    countFallbacks(skipped, source.getSource());
                    return manifest;
                }
                continue;
            }
            AgentSkill skill = read(source, skipped, () -> source.getSkill(id), null);
            if (skill != null) {
                countFallbacks(skipped, source.getSource());
                return Optional.of(SkillManifests.fromSkill(skill));
            }
        }
        countFallbacks(skipped, NONE);
        return Optional.empty();
    }

    @Override
    public Map<String, String> nameById() {
        List<String> skipped = new ArrayList<>();
        Map<String, String> merged = new LinkedHashMap<>();
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                read(source, skipped, catalog::nameById, Map.<String, String>of())
                        .forEach(merged::putIfAbsent);
                continue;
            }
            for (String name :
                    read(source, skipped, source::getAllSkillNames, List.<String>of())) {
                merged.putIfAbsent(name, name);
            }
        }
        countFallbacks(skipped, MERGED);
        return Collections.unmodifiableMap(merged);
    }

    /**
     * 单源读取的降级闸门。
     *
     * <p>只吞 {@link PayrollUnavailableException}：来源连不上可以让后继来源顶上，技能内容本身
     * 有问题（{@code PayrollException}）必须原样抛给调用方，否则管理台会把「存坏了」显示成
     * 「没有这个技能」。跳过一定计数——规格 §7.1 不允许无声降级。
     */
    private <T> T read(
            AgentSkillRepository source, List<String> skipped, Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (PayrollUnavailableException e) {
            log.warn(
                    "[Skill] 技能源 {} 不可用，跳过并尝试下一个来源：{}",
                    source.getSource(),
                    e.getMessage());
            skipped.add(source.getSource());
            return fallback;
        }
    }

    private static void countFallbacks(List<String> skipped, String to) {
        skipped.forEach(from -> SkillMetrics.sourceFallback(from, to));
    }

    @Override
    public void invalidate() {
        for (AgentSkillRepository source : sources) {
            if (source instanceof PayrollSkillCatalog catalog) {
                catalog.invalidate();
            }
        }
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
