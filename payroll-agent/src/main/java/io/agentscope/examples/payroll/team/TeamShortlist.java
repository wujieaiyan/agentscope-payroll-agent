package io.agentscope.examples.payroll.team;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 选角短名单：每个角色最多 3 个候选，计划生成只能在其中选人。 */
public record TeamShortlist(
        String templateId,
        Map<TeamRole, List<AgentCandidate>> byRole,
        String scoreJson,
        int windowDays,
        boolean scoringAvailable) {

    public TeamShortlist {
        templateId = templateId == null ? "" : templateId;
        byRole = byRole == null ? Map.of() : Map.copyOf(byRole);
        scoreJson = scoreJson == null ? "{}" : scoreJson;
        windowDays = Math.max(1, windowDays);
    }

    public List<AgentCandidate> candidates(TeamRole role) {
        return byRole.getOrDefault(role, List.of());
    }

    public AgentCandidate top(TeamRole role) {
        List<AgentCandidate> candidates = candidates(role);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public Optional<AgentCandidate> find(TeamRole role, String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return candidates(role).stream()
                .filter(candidate -> candidate.agentId().equals(agentId))
                .findFirst();
    }

    public boolean complete(List<TeamRole> requiredRoles) {
        if (requiredRoles == null) {
            return true;
        }
        return requiredRoles.stream().allMatch(role -> !candidates(role).isEmpty());
    }

    public boolean containsAgent(String agentId) {
        return byRole.values().stream()
                .flatMap(List::stream)
                .anyMatch(candidate -> candidate.agentId().equals(agentId));
    }
}
