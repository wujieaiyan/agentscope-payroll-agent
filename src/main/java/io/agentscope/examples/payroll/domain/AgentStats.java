package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/**
 * 单个智能体在统计窗口内的运行评分。
 *
 * <p>objectiveScore 对应规格 §7.3 的 O，feedbackScore 对应 §7.4 的 F，
 * finalScore 是 §7.5 冷启动收缩后的结果；样本不足时 coldStart=true。
 */
public record AgentStats(
        String agentId,
        String name,
        String role,
        int samples,
        boolean coldStart,
        double successRate,
        double successLowerBound,
        double toolOkLowerBound,
        double validationLowerBound,
        long p50LatencyMs,
        long latencyTargetMs,
        double latencyScore,
        double stabilityScore,
        double objectiveScore,
        int upVotes,
        double downWeight,
        double feedbackScore,
        double empiricalScore,
        double priorScore,
        double finalScore,
        LocalDateTime lastSeenAt) {

    public AgentStats {
        agentId = agentId == null ? "" : agentId;
        name = name == null || name.isBlank() ? agentId : name;
        role = role == null ? "" : role;
    }
}
