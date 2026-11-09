package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 智能体评分榜快照，按 finalScore 降序；cached 表示是否命中 5 分钟缓存。 */
public record AgentStatsSnapshot(
        int windowDays,
        LocalDateTime generatedAt,
        boolean cached,
        int recordCount,
        int feedbackCount,
        List<AgentStats> agents) {

    public AgentStatsSnapshot {
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public AgentStatsSnapshot asCached() {
        return new AgentStatsSnapshot(
                windowDays, generatedAt, true, recordCount, feedbackCount, agents);
    }
}
