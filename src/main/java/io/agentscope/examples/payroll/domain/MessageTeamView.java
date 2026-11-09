package io.agentscope.examples.payroll.domain;

import java.util.List;

/** 单条助手消息的团队运行视图：主运行、全部运行、节点摘要、追溯引用与当前反馈。 */
public record MessageTeamView(
        long sessionId,
        long messageId,
        AgentTeamRun primaryRun,
        List<AgentTeamRun> runs,
        List<AgentRunRecord> nodes,
        List<ChatToolTrace> traces,
        ChatFeedback feedback) {

    public MessageTeamView {
        runs = runs == null ? List.of() : List.copyOf(runs);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}
