package io.agentscope.examples.payroll.team;

import java.util.Set;

/** 某个角色下的候选智能体；C/O/F 与 rankScore 用于生成短名单。 */
public record AgentCandidate(
        String agentId,
        String name,
        TeamRole role,
        Set<String> capabilities,
        double capabilityScore,
        double objectiveScore,
        double feedbackScore,
        double rankScore,
        int samples,
        boolean coldStart,
        String latencyClass) {

    public AgentCandidate {
        agentId = agentId == null ? "" : agentId;
        name = name == null || name.isBlank() ? agentId : name;
        role = role == null ? TeamRole.QUERY : role;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        capabilityScore = between(capabilityScore, 0D, 1D);
        objectiveScore = between(objectiveScore, 0D, 100D);
        feedbackScore = between(feedbackScore, 0D, 100D);
        rankScore = between(rankScore, 0D, 1D);
        samples = Math.max(0, samples);
    }

    private static double between(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
