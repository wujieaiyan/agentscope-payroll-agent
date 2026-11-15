package io.agentscope.examples.payroll.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 合并顺序：薪酬 > L0 强制 > L1 语义 > L2 模型。 */
class KbRoutePlannerTest {

    private static final SemanticRouteClassifier.Decision KB_READ =
            confident(SemanticRouteClassifier.LABEL_KB_READ);
    private static final SemanticRouteClassifier.Decision KB_ADMIN =
            confident(SemanticRouteClassifier.LABEL_KB_ADMIN);
    private static final SemanticRouteClassifier.Decision OTHER =
            confident(SemanticRouteClassifier.LABEL_OTHER);
    private static final SemanticRouteClassifier.Decision UNSURE =
            new SemanticRouteClassifier.Decision(false, "kb_read", 0.5, 0.01, "低置信");

    @Test
    @DisplayName("薪酬轮次直接终止，语义与模型都不参与")
    void payrollShortCircuits() {
        KbRoutePlanner.Plan plan =
                KbRoutePlanner.plan(true, forced(KbRoutingPolicy.Decision.FORCE_EXPLICIT), KB_READ, router(true));
        assertFalse(plan.need());
        assertEquals(RouteDecision.KB_NONE, plan.kind());
        assertEquals("", plan.source());
    }

    @Test
    @DisplayName("显式要求与追问延续不可被语义改写")
    void forcedExplicitAndFollowUpAreImmutable() {
        for (KbRoutingPolicy.Decision decision :
                new KbRoutingPolicy.Decision[] {
                    KbRoutingPolicy.Decision.FORCE_EXPLICIT, KbRoutingPolicy.Decision.FORCE_FOLLOW_UP
                }) {
            for (SemanticRouteClassifier.Decision semantic :
                    new SemanticRouteClassifier.Decision[] {KB_READ, KB_ADMIN, OTHER}) {
                KbRoutePlanner.Plan plan =
                        KbRoutePlanner.plan(false, forced(decision), semantic, router(false));
                assertTrue(plan.need(), decision + "/" + semantic.label());
                assertEquals(RouteDecision.KB_READ, plan.kind());
                assertEquals(RouteDecision.KB_SOURCE_FORCE, plan.source());
            }
        }
    }

    @Test
    @DisplayName("高置信 kb_admin 纠正关键词误召回，other 与低置信不纠正")
    void keywordCorrectedOnlyByAdminSignal() {
        KbRoutePlanner.Plan admin =
                KbRoutePlanner.plan(false, forced(KbRoutingPolicy.Decision.FORCE_KEYWORD), KB_ADMIN, router(false));
        assertFalse(admin.need());
        assertEquals(RouteDecision.KB_ADMIN, admin.kind());
        assertEquals(SemanticRouteClassifier.SOURCE_SEMANTIC, admin.source());

        for (SemanticRouteClassifier.Decision semantic :
                new SemanticRouteClassifier.Decision[] {KB_READ, OTHER, UNSURE}) {
            KbRoutePlanner.Plan plan =
                    KbRoutePlanner.plan(
                            false, forced(KbRoutingPolicy.Decision.FORCE_KEYWORD), semantic, router(false));
            assertTrue(plan.need(), semantic.label());
            assertEquals(RouteDecision.KB_SOURCE_FORCE, plan.source());
        }
    }

    @Test
    @DisplayName("语义层在 L0 未强制时接管 need_kb，并给出来源")
    void semanticTakesOverWhenL0Silent() {
        KbRoutingPolicy.Result none = new KbRoutingPolicy.Result(
                KbRoutingPolicy.Decision.NONE, KbRoutingPolicy.Source.DEFAULT, false);

        KbRoutePlanner.Plan read = KbRoutePlanner.plan(false, none, KB_READ, router(false));
        assertTrue(read.need());
        assertEquals(RouteDecision.KB_READ, read.kind());
        assertEquals(SemanticRouteClassifier.SOURCE_SEMANTIC, read.source());

        KbRoutePlanner.Plan admin = KbRoutePlanner.plan(false, none, KB_ADMIN, router(true));
        assertFalse(admin.need());
        assertEquals(RouteDecision.KB_ADMIN, admin.kind());

        KbRoutePlanner.Plan other = KbRoutePlanner.plan(false, none, OTHER, router(true));
        assertFalse(other.need());
        assertEquals(RouteDecision.KB_NONE, other.kind());
        assertEquals(SemanticRouteClassifier.SOURCE_SEMANTIC, other.source());
    }

    @Test
    @DisplayName("语义不可用或低置信时退回 L2 模型判断")
    void fallsBackToRouter() {
        KbRoutingPolicy.Result none = new KbRoutingPolicy.Result(
                KbRoutingPolicy.Decision.NONE, KbRoutingPolicy.Source.DEFAULT, false);
        for (SemanticRouteClassifier.Decision semantic :
                new SemanticRouteClassifier.Decision[] {
                    null, UNSURE, SemanticRouteClassifier.Decision.unavailable("超时")
                }) {
            KbRoutePlanner.Plan plan = KbRoutePlanner.plan(false, none, semantic, router(true));
            assertTrue(plan.need());
            assertEquals(RouteDecision.KB_SOURCE_ROUTER, plan.source());

            KbRoutePlanner.Plan noRoute = KbRoutePlanner.plan(false, none, semantic, null);
            assertFalse(noRoute.need());
            assertEquals(RouteDecision.KB_NONE, noRoute.kind());
        }
    }

    @Test
    @DisplayName("只有 L0 未强制时调用语义层，预览与真实链路共用同一门禁")
    void semanticEligibilityFollowsL0() {
        assertFalse(KbRoutePlanner.semanticEligible(null, false));
        assertFalse(KbRoutePlanner.semanticEligible(forced(KbRoutingPolicy.Decision.NONE), true));
        for (KbRoutingPolicy.Decision decision :
                new KbRoutingPolicy.Decision[] {
                    KbRoutingPolicy.Decision.NONE,
                    KbRoutingPolicy.Decision.CANDIDATE_BOUND,
                    KbRoutingPolicy.Decision.FORCE_KEYWORD
                }) {
            assertTrue(KbRoutePlanner.semanticEligible(forced(decision), false), decision.name());
        }
        for (KbRoutingPolicy.Decision decision :
                new KbRoutingPolicy.Decision[] {
                    KbRoutingPolicy.Decision.FORCE_EXPLICIT,
                    KbRoutingPolicy.Decision.FORCE_FOLLOW_UP,
                    KbRoutingPolicy.Decision.SKIP_PAYROLL
                }) {
            assertFalse(KbRoutePlanner.semanticEligible(forced(decision), false), decision.name());
        }
    }

    @Test
    @DisplayName("L2 直接给 admin 时不检索")
    void routerAdminDoesNotSearch() {
        RouteDecision route =
                new RouteDecision(
                        RouteDecision.TARGET_AGENT,
                        null,
                        "管理知识库",
                        0.9,
                        null,
                        true,
                        RouteDecision.KB_ADMIN,
                        RouteDecision.KB_SOURCE_ROUTER);
        KbRoutePlanner.Plan plan = KbRoutePlanner.plan(false, null, null, route);
        assertFalse(plan.need());
        assertEquals(RouteDecision.KB_ADMIN, plan.kind());
    }

    @Test
    @DisplayName("候选绑定阶段也允许语义升级为检索")
    void candidateBoundStillConsultsSemantic() {
        KbRoutingPolicy.Result bound = new KbRoutingPolicy.Result(
                KbRoutingPolicy.Decision.CANDIDATE_BOUND, KbRoutingPolicy.Source.BOUND, true);
        KbRoutePlanner.Plan plan = KbRoutePlanner.plan(false, bound, KB_READ, router(false));
        assertTrue(plan.need());
        assertEquals(SemanticRouteClassifier.SOURCE_SEMANTIC, plan.source());
    }

    private static SemanticRouteClassifier.Decision confident(String label) {
        return new SemanticRouteClassifier.Decision(true, label, 0.8, 0.2, "ok");
    }

    private static KbRoutingPolicy.Result forced(KbRoutingPolicy.Decision decision) {
        return new KbRoutingPolicy.Result(decision, KbRoutingPolicy.Source.KEYWORD, false);
    }

    private static RouteDecision router(boolean needKb) {
        return new RouteDecision(
                RouteDecision.TARGET_AGENT,
                null,
                "",
                0.9,
                null,
                needKb,
                needKb ? RouteDecision.KB_READ : RouteDecision.KB_NONE,
                RouteDecision.KB_SOURCE_ROUTER);
    }
}
