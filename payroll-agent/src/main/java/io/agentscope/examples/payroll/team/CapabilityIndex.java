package io.agentscope.examples.payroll.team;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 能力索引：内置角色映射优先，其次技能/标签/描述关键词推断。
 *
 * <p>P1 不新增 Nacos 字段；显式声明能力留给后续阶段。推断结果只用于辅助位，
 * 不可担任计算/验证/入账等强制角色。
 */
public final class CapabilityIndex {

    private static final Map<String, Set<String>> BUILTIN_CAPABILITIES =
            Map.of(
                    RoleSkills.CALCULATOR,
                    Set.of(TeamIntent.CAP_PAYROLL_CALC, TeamIntent.CAP_PAYROLL_QUERY),
                    RoleSkills.VALIDATOR,
                    Set.of(TeamIntent.CAP_PAYROLL_VALIDATE),
                    RoleSkills.ACCOUNTANT,
                    Set.of(TeamIntent.CAP_ACCOUNTING, TeamIntent.CAP_PAYROLL_QUERY));

    private static final Map<String, Set<String>> KEYWORD_CAPABILITIES = keywords();

    private final List<AgentDefinition> catalog;
    private final AgentSkillRepository skills;
    private final Map<String, AgentCapabilityProfile> cache = new LinkedHashMap<>();

    public CapabilityIndex(List<AgentDefinition> catalog, AgentSkillRepository skills) {
        this.catalog = catalog == null ? List.of() : List.copyOf(catalog);
        this.skills = skills;
        this.catalog.forEach(agent -> cache.put(agent.id(), build(agent)));
    }

    public static CapabilityIndex from(List<AgentDefinition> catalog) {
        return new CapabilityIndex(catalog, SkillRepositories.shared());
    }

    public List<AgentCapabilityProfile> profiles() {
        return List.copyOf(cache.values());
    }

    public AgentCapabilityProfile profile(String agentId) {
        return agentId == null ? null : cache.get(agentId);
    }

    public AgentDefinition definition(String agentId) {
        return catalog.stream()
                .filter(agent -> agent.id().equals(agentId))
                .findFirst()
                .orElse(null);
    }

    /** 至少一个技能能解析（Nacos 或内置）才允许进入自动团队。 */
    public boolean skillsUsable(AgentDefinition agent) {
        if (agent == null || agent.skillIds().isEmpty()) {
            return false;
        }
        for (String skillId : agent.skillIds()) {
            if (RoleSkills.ids().contains(skillId)) {
                return true;
            }
            if (skills != null && skills.getSkill(skillId) != null) {
                return true;
            }
        }
        return false;
    }

    private AgentCapabilityProfile build(AgentDefinition agent) {
        Set<String> capabilities = new LinkedHashSet<>();
        boolean builtin = false;
        for (String skillId : agent.skillIds()) {
            Set<String> mapped = BUILTIN_CAPABILITIES.get(skillId);
            if (mapped != null) {
                capabilities.addAll(mapped);
                builtin = true;
            }
        }
        String evidence = evidence(agent);
        if (!builtin) {
            for (Map.Entry<String, Set<String>> entry : KEYWORD_CAPABILITIES.entrySet()) {
                if (entry.getValue().stream().anyMatch(evidence::contains)) {
                    capabilities.add(entry.getKey());
                }
            }
        }
        String source =
                builtin
                        ? AgentCapabilityProfile.SOURCE_BUILTIN
                        : AgentCapabilityProfile.SOURCE_INFERRED;
        String latency = latency(capabilities);
        return new AgentCapabilityProfile(
                agent.id(),
                agent.name(),
                capabilities,
                taskTypes(capabilities),
                latency,
                builtin,
                source);
    }

    private String evidence(AgentDefinition agent) {
        StringBuilder text = new StringBuilder();
        text.append(agent.name() == null ? "" : agent.name()).append(' ');
        text.append(agent.description() == null ? "" : agent.description()).append(' ');
        text.append(String.join(" ", agent.tags())).append(' ');
        text.append(String.join(" ", agent.skillIds()));
        if (skills != null) {
            for (String skillId : agent.skillIds()) {
                AgentSkill skill = skills.getSkill(skillId);
                if (skill != null) {
                    text.append(' ')
                            .append(skill.getDescription() == null ? "" : skill.getDescription())
                            .append(' ')
                            .append(skill.getSkillContent() == null ? "" : skill.getSkillContent());
                }
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String latency(Set<String> capabilities) {
        if (capabilities.contains(TeamIntent.CAP_PAYROLL_CALC)
                || capabilities.contains(TeamIntent.CAP_PAYROLL_VALIDATE)
                || capabilities.contains(TeamIntent.CAP_ACCOUNTING)) {
            return AgentCapabilityProfile.LATENCY_NORMAL;
        }
        if (capabilities.contains(TeamIntent.CAP_PAYROLL_QUERY)
                || capabilities.contains(TeamIntent.CAP_POLICY_KNOWLEDGE)
                || capabilities.contains(TeamIntent.CAP_KB_CONFIG)) {
            return AgentCapabilityProfile.LATENCY_FAST;
        }
        return AgentCapabilityProfile.LATENCY_NORMAL;
    }

    private static Set<String> taskTypes(Set<String> capabilities) {
        Set<String> types = new LinkedHashSet<>();
        if (capabilities.contains(TeamIntent.CAP_PAYROLL_CALC)) {
            types.add(TeamIntent.TASK_CALCULATE);
        }
        if (capabilities.contains(TeamIntent.CAP_PAYROLL_VALIDATE)) {
            types.add(TeamIntent.TASK_REVIEW);
        }
        if (capabilities.contains(TeamIntent.CAP_ACCOUNTING)) {
            types.add(TeamIntent.TASK_CALCULATE);
        }
        if (capabilities.contains(TeamIntent.CAP_PAYROLL_QUERY)) {
            types.add(TeamIntent.TASK_PAYSLIP_QUERY);
            types.add(TeamIntent.TASK_BATCH_QUERY);
        }
        if (capabilities.contains(TeamIntent.CAP_POLICY_KNOWLEDGE)) {
            types.add(TeamIntent.TASK_POLICY_EXPLAIN);
        }
        if (capabilities.contains(TeamIntent.CAP_KB_CONFIG)) {
            types.add(TeamIntent.TASK_KB_CONFIG);
        }
        return Set.copyOf(types);
    }

    private static Map<String, Set<String>> keywords() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put(
                TeamIntent.CAP_PAYROLL_CALC,
                Set.of("工资", "薪资", "薪酬", "核算", "个税", "社保", "公积金", "计算", "payroll"));
        map.put(
                TeamIntent.CAP_PAYROLL_VALIDATE,
                Set.of("复核", "验证", "校验", "审计", "勾稽", "validate"));
        map.put(
                TeamIntent.CAP_ACCOUNTING,
                Set.of("入账", "记账", "凭证", "汇总", "accounting", "posting"));
        map.put(
                TeamIntent.CAP_PAYROLL_QUERY,
                Set.of("查询", "明细", "工资条", "考勤", "档案", "员工", "query", "payslip"));
        map.put(
                TeamIntent.CAP_POLICY_KNOWLEDGE,
                Set.of("政策", "规则", "解释", "知识", "法规", "policy", "knowledge"));
        map.put(
                TeamIntent.CAP_KB_CONFIG,
                Set.of("知识库", "建库", "分块", "检索配置", "topk", "入库任务", "重建索引", "kb"));
        return Map.copyOf(map);
    }

    /** 供测试与报告使用：按角色列出具备该能力且可解析技能的画像。 */
    public List<AgentCapabilityProfile> candidatesFor(TeamRole role) {
        List<AgentCapabilityProfile> result = new ArrayList<>();
        for (AgentCapabilityProfile profile : cache.values()) {
            AgentDefinition agent = definition(profile.agentId());
            if (agent == null || !skillsUsable(agent)) {
                continue;
            }
            if (profile.supports(role.capability())) {
                result.add(profile);
            }
        }
        return result;
    }
}
