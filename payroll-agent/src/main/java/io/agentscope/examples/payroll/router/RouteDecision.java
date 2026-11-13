package io.agentscope.examples.payroll.router;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.util.Locale;

/**
 * 一次智能体路由决策。
 *
 * <p>{@code failureReason} 非空表示路由未生效（模型不可用、输出非法或置信度不足），
 * 调用方沿用既有静态链路，并把原因写入路由快照。
 */
public record RouteDecision(
        String target,
        AgentDefinition agent,
        String reason,
        double confidence,
        String failureReason) {

    public static final String TARGET_AGENT = "agent";
    public static final String TARGET_TEAM = "team";
    public static final String TARGET_PIPELINE = "pipeline";

    public RouteDecision {
        reason = reason == null ? "" : reason.trim();
        confidence = Math.max(0D, Math.min(1D, confidence));
    }

    public static RouteDecision fallback(String failureReason) {
        String value =
                failureReason == null || failureReason.isBlank() ? "路由未生效" : failureReason.trim();
        return new RouteDecision(null, null, "", 0D, value);
    }

    public boolean routed() {
        return target != null && failureReason == null;
    }

    /** 写入 {@code ChatRouting.definitionSignature} 的路由标记。 */
    public String signature() {
        if (!routed()) {
            return "";
        }
        return "router:" + target + (agent == null ? "" : ":" + agent.id());
    }

    /** 写入 {@code ChatRouting.reason} 的一句话说明，附带置信度便于事后解释。 */
    public String note() {
        return "智能体路由决定（置信度 "
                + String.format(Locale.ROOT, "%.2f", confidence)
                + "）："
                + (reason.isBlank() ? "模型未给出理由" : reason);
    }
}
