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
        List<String> stages,
        Kb kb) {

    /**
     * 本轮知识库判定：{@code source} 为 router / force / fallback，{@code scope} 为 session / all，
     * {@code reroute} 表示模型选中了不具备检索能力的智能体、被显式改道到检索智能体。
     */
    public record Kb(boolean need, String kind, String source, String scope, boolean reroute) {

        public Kb {
            kind = kind == null || kind.isBlank() ? "none" : kind;
            source = source == null ? "" : source;
            scope = scope == null ? "" : scope;
        }
    }

    public static final String PIPELINE = "PIPELINE";
    public static final String AGENT = "AGENT";
    public static final String FLOW = "FLOW";
    public static final String GRAPH = "GRAPH";
    public static final String TEAM_ACTIVE = "TEAM_ACTIVE";
    public static final String ACTION = "ACTION";

    public ChatRouting(
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
        this(
                path,
                persona,
                reason,
                fromPath,
                fallbackReason,
                model,
                definitionSignature,
                agentIds,
                teamRunId,
                elapsedMs,
                stages,
                null);
    }

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
