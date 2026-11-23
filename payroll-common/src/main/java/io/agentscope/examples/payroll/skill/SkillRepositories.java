package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 组装角色技能仓库：按 {@code payroll.skills.source} 选 Nacos 或技能表，缺失时回退代码内置版本。 */
public final class SkillRepositories {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositories.class);

    private static volatile AgentSkillRepository shared;

    private SkillRepositories() {
    }

    /**
     * 进程级共享仓库：智能体按定义缓存后，所有实例共用同一份 Nacos 快照与 HTTP 客户端。
     *
     * <p>{@link NacosSkillRepository} 使用并发缓存与 volatile 快照，TTL 到期刷新和
     * last-known-good 回退都按仓库实例生效；共享不会改变单次读取的语义。
     */
    public static AgentSkillRepository shared() {
        AgentSkillRepository repository = shared;
        if (repository != null) {
            return repository;
        }
        synchronized (SkillRepositories.class) {
            if (shared == null) {
                shared = create();
            }
            return shared;
        }
    }

    /** 清空共享仓库；仅用于测试或配置热切换。 */
    public static void resetShared() {
        synchronized (SkillRepositories.class) {
            shared = null;
        }
    }

    /**
     * 容器装配入口：把按 {@code payroll.skills.source} 建好的仓库放进共享槽位。
     *
     * <p>只有 db 源需要这一步——它的读路径要复用 Spring 的 {@code PayrollDatabase} 连接池，
     * 不能让 {@link #shared()} 的懒加载再开一个 Hikari 池。不关闭被替换掉的旧实例：它可能已被
     * 启动期的某个 Bean 持有引用（{@code CapabilityIndex} 就取过 {@link #shared()}），
     * 关掉了会让正在跑的请求莫名失败，而 Nacos 仓库本身不需要释放资源。
     */
    public static synchronized void install(AgentSkillRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("技能仓库不能为空");
        }
        AgentSkillRepository previous = shared;
        if (previous != null && previous != repository) {
            log.warn(
                    "[Skill] 共享技能仓库已在容器装配前创建（source={}），替换为配置指定的 source={}",
                    previous.getSource(),
                    repository.getSource());
        }
        shared = repository;
    }

    /**
     * 管理台写入后丢弃共享仓库的目录与正文快照，让本节点下一轮对话立刻读到新技能。
     *
     * <p>不创建实例：没有共享仓库就没有缓存需要联动。
     */
    public static void invalidateShared() {
        AgentSkillRepository repository = shared;
        if (repository instanceof PayrollSkillCatalog catalog) {
            catalog.invalidate();
            SkillMetrics.invalidation("write");
        }
    }

    public static AgentSkillRepository create() {
        return create(SkillsSource.fromEnvironment());
    }

    /**
     * 按来源建仓库（规格 §11.1）。
     *
     * <p>db 分支自己开库与 blob 目录，只服务命令行与测试；容器里应走 {@link #install}，
     * 让运行期读路径复用 Spring 的 {@code PayrollDatabase} 连接池。
     */
    public static AgentSkillRepository create(SkillsSource source) {
        if (source == SkillsSource.DB) {
            PayrollDatabase database = PayrollDatabase.openDefault();
            JdbcSkillStore store =
                    new JdbcSkillStore(database, LocalDiskSkillBlobStore.fromEnvironment());
            log.info(
                    "[Skill] 角色提示词读取技能表 {}（文件字节：{}），缺失时回退代码内置版本",
                    store.location(),
                    store.blobLocation());
            return new CompositeSkillRepository(
                    List.of(new DbSkillRepository(store), new BuiltInSkillRepository()));
        }
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
     * 动态技能按完整 skillId（{@code <name>_<source>}）过滤。三个来源全放行：过滤器只决定系统提示里
     * 列出哪些技能，多放一个当前不存在的后缀没有副作用，而漏掉 {@code _db} 会让切库后的智能体
     * 一个技能都不列（技能在、提示词里没有，表现为「角色不按技能干活」）。
     */
    public static SkillFilter filterFor(String skillId) {
        return filterFor(List.of(skillId));
    }

    /** 智能体可绑定多个技能，过滤器需要同时放行这些技能的所有来源。 */
    public static SkillFilter filterFor(List<String> skillIds) {
        List<String> fullIds = new ArrayList<>(skillIds.size() * 3);
        for (String skillId : skillIds) {
            fullIds.add(skillId + "_" + NacosSkillRepository.SOURCE);
            fullIds.add(skillId + "_" + DbSkillRepository.SOURCE);
            fullIds.add(skillId + "_" + BuiltInSkillRepository.SOURCE);
        }
        return SkillFilter.only(fullIds.toArray(String[]::new));
    }

    /**
     * 把仓库视图限定为智能体绑定的技能。
     *
     * <p>AgentScope 的 {@link SkillFilter} 只影响系统提示中列出的技能，动态加载工具仍会注册
     * 仓库里的全部技能，模型可以加载任意未绑定的角色技能。流程节点和自定义智能体必须使用
     * 限定视图，才能保证“创建了 ai-coding 智能体却加载到薪酬技能”不会发生。
     */
    public static AgentSkillRepository scoped(
            AgentSkillRepository repository, List<String> skillIds) {
        if (repository == null || skillIds == null || skillIds.isEmpty()) {
            return repository;
        }
        // LinkedHashSet 而不是 Set.copyOf：范围视图列出的技能按绑定顺序出。Set.copyOf 的迭代顺序
        // 每次 JVM 都可能不同，会让同一份定义的提示词在重启后换个技能顺序。
        return new ScopedSkillRepository(repository, new LinkedHashSet<>(skillIds));
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

    /**
     * 绑定范围视图：目录与批量取正文都只放行绑定技能，避免自定义智能体加载到其他角色技能。
     */
    private static final class ScopedSkillRepository
            implements AgentSkillRepository, PayrollSkillCatalog {

        private final AgentSkillRepository delegate;
        private final Set<String> allowed;

        private ScopedSkillRepository(AgentSkillRepository delegate, Set<String> allowed) {
            this.delegate = delegate;
            this.allowed = allowed;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return name != null && allowed.contains(name) ? delegate.getSkill(name) : null;
        }

        @Override
        public List<String> getAllSkillNames() {
            if (delegate instanceof PayrollSkillCatalog catalog) {
                return catalog.listManifests().stream()
                        .map(SkillManifest::id)
                        .filter(allowed::contains)
                        .toList();
            }
            return delegate.getAllSkillNames().stream().filter(allowed::contains).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return getSkills(allowed);
        }

        @Override
        public List<SkillManifest> listManifests() {
            if (delegate instanceof PayrollSkillCatalog catalog) {
                return catalog.listManifests().stream()
                        .filter(manifest -> allowed.contains(manifest.id()))
                        .toList();
            }
            List<SkillManifest> manifests = new ArrayList<>();
            for (String id : allowed) {
                AgentSkill skill = delegate.getSkill(id);
                if (skill != null) {
                    manifests.add(SkillManifests.fromSkill(skill));
                }
            }
            return manifests;
        }

        @Override
        public List<AgentSkill> getSkills(Collection<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<String> permitted = new ArrayList<>(ids.size());
            for (String id : ids) {
                if (allowed.contains(id)) {
                    permitted.add(id);
                }
            }
            if (permitted.isEmpty()) {
                return List.of();
            }
            if (delegate instanceof PayrollSkillCatalog catalog) {
                return catalog.getSkills(permitted);
            }
            List<AgentSkill> skills = new ArrayList<>(permitted.size());
            for (String id : permitted) {
                AgentSkill skill = delegate.getSkill(id);
                if (skill != null) {
                    skills.add(skill);
                }
            }
            return skills;
        }

        @Override
        public Optional<SkillManifest> manifest(String id) {
            if (id == null || !allowed.contains(id)) {
                return Optional.empty();
            }
            if (delegate instanceof PayrollSkillCatalog catalog) {
                return catalog.manifest(id);
            }
            return Optional.ofNullable(delegate.getSkill(id)).map(SkillManifests::fromSkill);
        }

        @Override
        public Map<String, String> nameById() {
            Map<String, String> names = new LinkedHashMap<>();
            if (delegate instanceof PayrollSkillCatalog catalog) {
                catalog.nameById()
                        .forEach(
                                (id, name) -> {
                                    if (allowed.contains(id)) {
                                        names.put(id, name);
                                    }
                                });
            } else {
                for (String id : allowed) {
                    AgentSkill skill = delegate.getSkill(id);
                    if (skill != null) {
                        names.put(id, skill.getName());
                    }
                }
            }
            return Collections.unmodifiableMap(names);
        }

        @Override
        public void invalidate() {
            if (delegate instanceof PayrollSkillCatalog catalog) {
                catalog.invalidate();
            }
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            return delegate.save(skills, overwrite);
        }

        @Override
        public boolean delete(String name) {
            return delegate.delete(name);
        }

        @Override
        public boolean skillExists(String name) {
            return getSkill(name) != null;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return delegate.getRepositoryInfo();
        }

        @Override
        public String getSource() {
            return delegate.getSource();
        }

        @Override
        public void setWriteable(boolean writeable) {
            delegate.setWriteable(writeable);
        }

        @Override
        public boolean isWriteable() {
            return delegate.isWriteable();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
