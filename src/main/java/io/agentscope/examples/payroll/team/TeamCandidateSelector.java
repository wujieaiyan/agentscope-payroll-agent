package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentStats;
import io.agentscope.examples.payroll.domain.AgentStatsSnapshot;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 候选过滤与评分：硬条件过滤后，按 C/O/F 计算 rankScore，每角色取 Top-3。
 *
 * <p>O/F 来自 {@link AgentStatsService} 的 30 天聚合；统计不可用时退化为能力匹配 +
 * 角色先验（规格 §10 L4）。
 */
public final class TeamCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(TeamCandidateSelector.class);
    static final String SCORE_VERSION = "p1-shadow-1";
    private static final int TOP_K = 3;

    private static final Map<TeamRole, Double> ROLE_PRIORS =
            Map.of(
                    TeamRole.CALCULATOR, 78D,
                    TeamRole.VALIDATOR, 80D,
                    TeamRole.ACCOUNTANT, 75D,
                    TeamRole.QUERY, 70D,
                    TeamRole.KNOWLEDGE, 70D);
    private static final double DEFAULT_PRIOR = 65D;
    private static final double DEFAULT_FEEDBACK = 65D;

    private final CapabilityIndex index;
    private final AgentStatsService statsService;
    private final int windowDays;

    public TeamCandidateSelector(
            CapabilityIndex index, AgentStatsService statsService, TeamSettings settings) {
        this.index = index;
        this.statsService = statsService;
        this.windowDays = settings == null ? 30 : settings.statsWindowDays();
    }

    public TeamShortlist shortlist(
            TeamIntent intent, String templateId, List<TeamRole> roles) {
        Map<String, AgentStats> stats = stats();
        boolean scoringAvailable = stats != null;
        Map<String, AgentStats> byAgent = stats == null ? Map.of() : stats;
        Map<TeamRole, List<AgentCandidate>> byRole = new EnumMap<>(TeamRole.class);
        for (TeamRole role : roles) {
            List<AgentCandidate> candidates = new ArrayList<>();
            for (AgentCapabilityProfile profile : index.profiles()) {
                AgentDefinition agent = index.definition(profile.agentId());
                if (!eligible(agent, profile, role)) {
                    continue;
                }
                AgentCandidate candidate = score(profile, role, byAgent.get(profile.agentId()));
                if (candidate.capabilityScore() > 0D) {
                    candidates.add(candidate);
                }
            }
            candidates.sort(
                    Comparator.comparingDouble(AgentCandidate::rankScore)
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(AgentCandidate::samples).reversed())
                            .thenComparing(AgentCandidate::agentId));
            byRole.put(role, List.copyOf(candidates.stream().limit(TOP_K).toList()));
        }
        String scoreJson = scoreJson(intent, byRole, scoringAvailable);
        log.info(
                "[动态团队] 候选短名单：task={}，template={}，统计可用={}，{}",
                intent.taskType(),
                templateId,
                scoringAvailable,
                byRole.entrySet().stream()
                        .map(
                                entry ->
                                        entry.getKey()
                                                + "="
                                                + entry.getValue().stream()
                                                        .map(AgentCandidate::agentId)
                                                        .toList())
                        .toList());
        return new TeamShortlist(
                templateId, byRole, scoreJson, windowDays, scoringAvailable);
    }

    private Map<String, AgentStats> stats() {
        if (statsService == null) {
            return null;
        }
        try {
            AgentStatsSnapshot snapshot = statsService.snapshot(windowDays);
            Map<String, AgentStats> byAgent = new LinkedHashMap<>();
            for (AgentStats stat : snapshot.agents()) {
                byAgent.put(stat.agentId(), stat);
            }
            return byAgent;
        } catch (RuntimeException e) {
            log.warn("[动态团队] 评分不可用，使用能力匹配与角色先验：{}", e.getMessage());
            return null;
        }
    }

    private boolean eligible(
            AgentDefinition agent, AgentCapabilityProfile profile, TeamRole role) {
        if (agent == null
                || !AgentDefinition.STATUS_ONLINE.equals(agent.status())
                || !AgentDefinition.VISIBILITY_PUBLIC.equals(agent.visibility())
                || !index.skillsUsable(agent)) {
            return false;
        }
        if (role.mandatory() && !profile.canBeMandatoryRole()) {
            return false;
        }
        return role.matches(profile.capabilities());
    }

    private AgentCandidate score(
            AgentCapabilityProfile profile, TeamRole role, AgentStats stat) {
        double capability = capabilityScore(profile, role);
        boolean hasSamples = stat != null && stat.samples() > 0;
        double objective = hasSamples ? stat.objectiveScore() : rolePrior(role);
        double feedback = hasSamples ? stat.feedbackScore() : DEFAULT_FEEDBACK;
        double rank = 0.50D * capability + 0.30D * (objective / 100D) + 0.20D * (feedback / 100D);
        return new AgentCandidate(
                profile.agentId(),
                profile.name(),
                role,
                profile.capabilities(),
                capability,
                objective,
                feedback,
                rank,
                hasSamples ? stat.samples() : 0,
                !hasSamples || stat.coldStart(),
                profile.latencyClass());
    }

    private static double capabilityScore(AgentCapabilityProfile profile, TeamRole role) {
        if (profile.supports(role.capability())) {
            return AgentCapabilityProfile.SOURCE_INFERRED.equals(profile.source()) ? 0.7D : 1.0D;
        }
        return role.matches(profile.capabilities()) ? 0.3D : 0D;
    }

    private static double rolePrior(TeamRole role) {
        return ROLE_PRIORS.getOrDefault(role, DEFAULT_PRIOR);
    }

    private String scoreJson(
            TeamIntent intent,
            Map<TeamRole, List<AgentCandidate>> byRole,
            boolean scoringAvailable) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("score_version", SCORE_VERSION);
        root.put("window_days", windowDays);
        root.put("scoring_available", scoringAvailable);
        root.put("task_type", intent.taskType());
        root.put("confidence", intent.confidence());
        Map<String, Object> roles = new LinkedHashMap<>();
        byRole.forEach(
                (role, candidates) -> {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (AgentCandidate candidate : candidates) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("agent_id", candidate.agentId());
                        item.put("name", candidate.name());
                        item.put("capability_score", candidate.capabilityScore());
                        item.put("objective_score", candidate.objectiveScore());
                        item.put("feedback_score", candidate.feedbackScore());
                        item.put("rank_score", candidate.rankScore());
                        item.put("samples", candidate.samples());
                        item.put("cold_start", candidate.coldStart());
                        item.put("latency_class", candidate.latencyClass());
                        items.add(item);
                    }
                    roles.put(role.name(), items);
                });
        root.put("roles", roles);
        return JsonSupport.toJson(root);
    }
}
