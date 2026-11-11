package io.agentscope.examples.payroll.domain;

import java.util.List;

/**
 * 一条助手消息的路由快照：本轮由哪条链路回答、是否发生回退，以及当时的模型与定义签名。
 *
 * <p>只在每轮结束时构造一次，落库后不再修改；旧消息没有该字段时为 null，绝不补默认值。
 */
public record ChatRouting(
        String path,
        String persona,
        String reason,
        String fromPath,
        String fallbackReason,
        String model,
        String definitionSignature,
        List<String> agentIds,
        long teamRunId,
        long elapsedMs,
        List<String> stages) {

    public static final String PIPELINE = "PIPELINE";
    public static final String AGENT = "AGENT";
    public static final String FLOW = "FLOW";
    public static final String GRAPH = "GRAPH";
    public static final String TEAM_ACTIVE = "TEAM_ACTIVE";
    public static final String ACTION = "ACTION";

    public ChatRouting {
        path = path == null || path.isBlank() ? PIPELINE : path;
        persona = persona == null ? "" : persona;
        reason = reason == null ? "" : reason;
        fromPath = fromPath == null || fromPath.isBlank() ? null : fromPath;
        fallbackReason =
                fallbackReason == null || fallbackReason.isBlank() ? null : fallbackReason;
        model = model == null ? "" : model;
        definitionSignature = definitionSignature == null ? "" : definitionSignature;
        agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
        elapsedMs = Math.max(0L, elapsedMs);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public boolean fellBack() {
        return fromPath != null;
    }

    /** 界面 chip 文案；技术细节留在折叠卡片与日志里。 */
    public String label() {
        return switch (path) {
            case AGENT -> "单智能体";
            case FLOW -> "流程";
            case GRAPH -> "图编排";
            case TEAM_ACTIVE -> "动态团队";
            case ACTION -> "操作执行";
            default -> "流水线";
        };
    }
}
