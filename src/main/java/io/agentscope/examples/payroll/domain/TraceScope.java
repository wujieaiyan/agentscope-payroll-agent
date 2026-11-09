package io.agentscope.examples.payroll.domain;

/**
 * 一次运行的追溯坐标：消息 → 运行 → 节点 → 工具调用。
 *
 * <p>runId 为 0 表示尚未进入团队运行（例如 OFF 模式），此时行为与旧链路一致。
 */
public record TraceScope(
        long runId,
        String planId,
        String nodeId,
        String agentId,
        String role,
        String mode,
        String stageKey) {

    public static final String MODE_OFF = "OFF";
    public static final String MODE_STATIC = "STATIC";
    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_ACTIVE = "ACTIVE";

    public static TraceScope none() {
        return new TraceScope(0L, null, null, null, null, MODE_OFF, null);
    }

    public boolean active() {
        return runId > 0;
    }
}
