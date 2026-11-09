package io.agentscope.examples.payroll.team;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 计划生成：模板槽位内选人，模型只能从 Top-3 短名单中选择；任何失败都退化为 Top-1。
 *
 * <p>生成后必须通过 {@link PlanValidator}，未通过则尝试一次确定性修复（Top-1 去重），
 * 仍失败则返回回退原因，由影子服务按 L0-L4 记录。
 */
public final class TeamPlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(TeamPlanGenerator.class);

    private final TeamLlmClient llm;
    private final PlanValidator validator;

    public TeamPlanGenerator(TeamLlmClient llm, PlanValidator validator) {
        this.llm = llm == null ? TeamLlmClient.disabled() : llm;
        this.validator = validator;
    }

    public PlanResult generate(TeamIntent intent, TeamShortlist shortlist, String planId) {
        TeamTemplate template =
                TeamTemplate.from(shortlist == null ? null : shortlist.templateId());
        if (template == null) {
            template = TeamTemplate.select(intent);
        }
        return generate(intent, template, shortlist, planId);
    }

    public PlanResult generate(
            TeamIntent intent, TeamTemplate template, TeamShortlist shortlist, String planId) {
        if (intent == null || !intent.eligible()) {
            return PlanResult.fallback("意图不满足动态组队条件", List.of());
        }
        if (template == null || template.fallback()) {
            return PlanResult.fallback("没有匹配的团队模板", List.of());
        }
        if (shortlist == null) {
            return PlanResult.fallback("候选短名单为空", List.of());
        }
        List<TeamRole> missing =
                template.slots().stream()
                        .filter(role -> shortlist.candidates(role).isEmpty())
                        .toList();
        if (!missing.isEmpty()) {
            return PlanResult.fallback(
                    "角色无可用候选：" + missing, List.of());
        }

        List<String> warnings = new ArrayList<>();
        PlanResult selected = selectWithLlm(intent, template, shortlist, planId, warnings);
        if (selected != null) {
            return selected;
        }
        return deterministic(intent, template, shortlist, planId, warnings);
    }

    private PlanResult selectWithLlm(
            TeamIntent intent,
            TeamTemplate template,
            TeamShortlist shortlist,
            String planId,
            List<String> warnings) {
        if (!llm.enabled()) {
            return null;
        }
        String json =
                llm.completeJson(
                        instruction(intent, template, shortlist),
                        intent.rawText(),
                        "plan:" + planId);
        if (json == null) {
            return null;
        }
        Map<TeamRole, String> bindings = parseSelection(json, template, shortlist);
        if (bindings == null) {
            warnings.add("模型选角不在短名单内，已回退 Top-1");
            return null;
        }
        String rationale = rationale(json);
        TeamPlan plan =
                TeamPlan.instantiate(
                        planId, intent.taskType(), template, bindings, rationale, warnings);
        PlanValidation validation = validator.validate(plan, intent);
        if (!validation.valid()) {
            warnings.add("模型计划未通过校验：" + validation.summary());
            log.warn(
                    "[动态团队] 模型计划未通过校验，回退 Top-1：plan={}，{}",
                    planId,
                    validation.summary());
            return null;
        }
        log.info(
                "[动态团队] 计划生成：plan={}，template={}，roles={}，来源=model",
                planId,
                template.id(),
                bindings);
        return PlanResult.ok(plan);
    }

    private PlanResult deterministic(
            TeamIntent intent,
            TeamTemplate template,
            TeamShortlist shortlist,
            String planId,
            List<String> warnings) {
        Map<TeamRole, String> bindings = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        List<TeamRole> unbound = new ArrayList<>();
        for (TeamRole role : template.slots()) {
            AgentCandidate candidate =
                    shortlist.candidates(role).stream()
                            .filter(item -> !used.contains(item.agentId()))
                            .findFirst()
                            .orElse(null);
            if (candidate == null) {
                unbound.add(role);
                continue;
            }
            bindings.put(role, candidate.agentId());
            used.add(candidate.agentId());
            if (candidate.coldStart()) {
                warnings.add("冷启动选角：" + candidate.agentId() + "（" + role.name() + "）");
            }
        }
        if (!unbound.isEmpty()) {
            return PlanResult.fallback("角色无可用候选（去重后）：" + unbound, List.of());
        }
        TeamPlan plan =
                TeamPlan.instantiate(
                        planId, intent.taskType(), template, bindings, "按评分 Top-1 组队", warnings);
        PlanValidation validation = validator.validate(plan, intent);
        if (!validation.valid()) {
            log.warn("[动态团队] Top-1 计划未通过校验：plan={}，{}", planId, validation.summary());
            return PlanResult.fallback("Top-1 计划未通过校验", validation.errors());
        }
        log.info(
                "[动态团队] 计划生成：plan={}，template={}，roles={}，来源=top1",
                planId,
                template.id(),
                bindings);
        return PlanResult.ok(plan);
    }

    private Map<TeamRole, String> parseSelection(
            String json, TeamTemplate template, TeamShortlist shortlist) {
        Map<String, Object> root;
        try {
            root = JsonSupport.fromJson(json, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException e) {
            log.warn("[动态团队] 计划 JSON 解析失败：{}", e.getMessage());
            return null;
        }
        Object rolesValue = root == null ? null : root.get("roles");
        if (!(rolesValue instanceof Map<?, ?> roles)) {
            return null;
        }
        Map<String, String> byRole = new LinkedHashMap<>();
        roles.forEach(
                (key, value) -> {
                    if (key instanceof String name && value instanceof String agentId) {
                        byRole.put(name.trim().toUpperCase(Locale.ROOT), agentId);
                    }
                });
        Map<TeamRole, String> bindings = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (TeamRole role : template.slots()) {
            String agentId = byRole.get(role.name());
            if (agentId == null || agentId.isBlank()) {
                return null;
            }
            if (shortlist.find(role, agentId).isEmpty()) {
                log.warn("[动态团队] 模型选择了短名单外的智能体：{} → {}", role, agentId);
                return null;
            }
            if (!used.add(agentId)) {
                log.warn("[动态团队] 模型重复选择智能体：{}", agentId);
                return null;
            }
            bindings.put(role, agentId);
        }
        return bindings;
    }

    private static String rationale(String json) {
        try {
            Map<String, Object> root =
                    JsonSupport.fromJson(json, new TypeReference<Map<String, Object>>() {});
            Object value = root == null ? null : root.get("rationale");
            if (value instanceof String text && !text.isBlank()) {
                String trimmed = text.trim();
                return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "…";
            }
        } catch (IllegalArgumentException e) {
            log.debug("[动态团队] 读取模型理由失败", e);
        }
        return "模型按评分与能力选择";
    }

    private static String instruction(
            TeamIntent intent, TeamTemplate template, TeamShortlist shortlist) {
        StringBuilder text = new StringBuilder();
        text.append("你是薪酬团队选角器。模板：")
                .append(template.id())
                .append("（")
                .append(template.label())
                .append("），槽位：")
                .append(template.slots())
                .append("。\n");
        text.append("任务类型：").append(intent.taskType()).append('\n');
        text.append("是否需要复核：").append(intent.needsValidation()).append('\n');
        text.append("每个槽位必须从下列候选里选一个 agent_id，不能跨角色、不能重复：\n");
        for (TeamRole role : template.slots()) {
            text.append("- ").append(role.name()).append("：");
            List<AgentCandidate> candidates = shortlist.candidates(role);
            List<String> items = new ArrayList<>();
            for (AgentCandidate candidate : candidates) {
                items.add(
                        String.format(
                                Locale.ROOT,
                                "%s(rank=%.3f, objective=%.1f, feedback=%.1f, samples=%d%s)",
                                candidate.agentId(),
                                candidate.rankScore(),
                                candidate.objectiveScore(),
                                candidate.feedbackScore(),
                                candidate.samples(),
                                candidate.coldStart() ? ", cold_start" : ""));
            }
            text.append(String.join("；", items)).append('\n');
        }
        text.append("输出 JSON：{\"roles\":{")
                .append(
                        template.slots().stream()
                                .map(role -> "\"" + role.name() + "\":\"agent_id\"")
                                .reduce((a, b) -> a + "," + b)
                                .orElse(""))
                .append("},\"rationale\":\"不超过50字\"}");
        return text.toString();
    }
}
