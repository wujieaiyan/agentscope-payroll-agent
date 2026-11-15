package io.agentscope.examples.payroll.router;

import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;

/**
 * 知识库路由决策合并：薪酬 &gt; L0 强制 &gt; L1 语义 &gt; L2 模型。
 *
 * <p>显式要求与追问延续不可被语义推翻；高置信 {@code kb_admin} 可纠正关键词误召回
 * （如“删除这个知识库”），语义 {@code other} 不推翻关键词安全网。
 */
public final class KbRoutePlanner {

    private KbRoutePlanner() {
    }

    public record Plan(boolean need, String kind, String source) {
    }

    public static final Plan NONE = new Plan(false, RouteDecision.KB_NONE, "");

    /** 仅 L0 未强制（或仅关键词命中，允许管理操作纠偏）时调用语义层；其余直接沿用 L0。 */
    public static boolean semanticEligible(KbRoutingPolicy.Result l0, boolean payroll) {
        if (payroll || l0 == null) {
            return false;
        }
        return switch (l0.decision()) {
            case NONE, CANDIDATE_BOUND, FORCE_KEYWORD -> true;
            default -> false;
        };
    }

    public static Plan plan(
            boolean payroll,
            KbRoutingPolicy.Result l0,
            SemanticRouteClassifier.Decision semantic,
            RouteDecision route) {
        if (payroll) {
            return NONE;
        }
        if (l0 != null && l0.forced()) {
            if (l0.decision() == KbRoutingPolicy.Decision.FORCE_KEYWORD
                    && isLabel(semantic, SemanticRouteClassifier.LABEL_KB_ADMIN)) {
                return new Plan(
                        false, RouteDecision.KB_ADMIN, SemanticRouteClassifier.SOURCE_SEMANTIC);
            }
            return new Plan(true, RouteDecision.KB_READ, RouteDecision.KB_SOURCE_FORCE);
        }
        if (semantic != null && semantic.confident()) {
            if (isLabel(semantic, SemanticRouteClassifier.LABEL_KB_READ)) {
                return new Plan(
                        true, RouteDecision.KB_READ, SemanticRouteClassifier.SOURCE_SEMANTIC);
            }
            if (isLabel(semantic, SemanticRouteClassifier.LABEL_KB_ADMIN)) {
                return new Plan(
                        false, RouteDecision.KB_ADMIN, SemanticRouteClassifier.SOURCE_SEMANTIC);
            }
            if (isLabel(semantic, SemanticRouteClassifier.LABEL_OTHER)) {
                return new Plan(
                        false, RouteDecision.KB_NONE, SemanticRouteClassifier.SOURCE_SEMANTIC);
            }
        }
        if (route == null) {
            return NONE;
        }
        if (!route.routed()) {
            return new Plan(false, RouteDecision.KB_NONE, RouteDecision.KB_SOURCE_FALLBACK);
        }
        boolean need = route.needKb() && !RouteDecision.KB_ADMIN.equals(route.kbKind());
        return new Plan(need, route.kbKind(), RouteDecision.KB_SOURCE_ROUTER);
    }

    private static boolean isLabel(
            SemanticRouteClassifier.Decision semantic, String label) {
        return semantic != null && semantic.confident() && label.equals(semantic.label());
    }
}
