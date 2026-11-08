package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        return filterFor(List.of(skillId));
    }

    /** 智能体可绑定多个技能，过滤器需要同时放行这些技能的两个来源。 */
    public static SkillFilter filterFor(List<String> skillIds) {
        List<String> fullIds = new ArrayList<>(skillIds.size() * 2);
        for (String skillId : skillIds) {
            fullIds.add(skillId + "_" + NacosSkillRepository.SOURCE);
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
        return new ScopedSkillRepository(repository, Set.copyOf(skillIds));
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

    private static final class ScopedSkillRepository implements AgentSkillRepository {

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
            return delegate.getAllSkillNames().stream().filter(allowed::contains).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return delegate.getAllSkills().stream()
                    .filter(skill -> allowed.contains(skill.getName()))
                    .toList();
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
