package io.agentscope.examples.payroll.router;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.util.Locale;

/**
 * 一次智能体路由决策。
 *
 * <p>{@code failureReason} 非空表示路由未生效（模型不可用、输出非法或置信度不足），
 * 调用方沿用既有静态链路，并把原因写入路由快照。
 *
 * <p>{@code needKb} / {@code kbKind} 是 P1 的知识库判定：{@code read} 表示本轮必须走具备
 * 只读检索的链路，{@code admin} 表示知识库管理操作（继续按 kb 标签选智能体、不检索）。
 * 薪酬轮次不读取这两个字段。
 */
public record RouteDecision(
        String target,
        AgentDefinition agent,
        String reason,
        double confidence,
        String failureReason,
        boolean needKb,
        String kbKind,
        String kbSource) {

    public static final String TARGET_AGENT = "agent";
    public static final String TARGET_TEAM = "team";
    public static final String TARGET_PIPELINE = "pipeline";

    public static final String KB_READ = "read";
    public static final String KB_ADMIN = "admin";
    public static final String KB_NONE = "none";

    /** 判定来源：模型输出、L0 强制覆盖、路由未生效时的回退。 */
    public static final String KB_SOURCE_ROUTER = "router";
    public static final String KB_SOURCE_FORCE = "force";
    public static final String KB_SOURCE_FALLBACK = "fallback";

    public RouteDecision {
        reason = reason == null ? "" : reason.trim();
        confidence = Math.max(0D, Math.min(1D, confidence));
        kbKind = normalizeKind(kbKind);
        kbSource = kbSource == null ? "" : kbSource.trim();
    }

    public static RouteDecision fallback(String failureReason) {
        String value =
                failureReason == null || failureReason.isBlank() ? "路由未生效" : failureReason.trim();
        return new RouteDecision(null, null, "", 0D, value, false, KB_NONE, KB_SOURCE_FALLBACK);
    }

    public boolean routed() {
        return target != null && failureReason == null;
    }

    /** 覆盖知识库判定，保留原有目标与理由；用于 L0 强制检索。 */
    public RouteDecision withKb(boolean need, String kind, String source) {
        return new RouteDecision(
                target, agent, reason, confidence, failureReason, need, kind, source);
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

    private static String normalizeKind(String kind) {
        if (kind == null) {
            return KB_NONE;
        }
        String value = kind.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case KB_READ, KB_ADMIN -> value;
            default -> KB_NONE;
        };
    }
}
