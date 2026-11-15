package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.router.KbRoutePlanner;
import io.agentscope.examples.payroll.router.RouteDecision;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbRouteOrderingTest {

    @Test
    @DisplayName("薪酬轮次不读 need_kb")
    void payrollNeverSearches() {
        KbRoutePlanner.Plan plan =
                KbRoutePlanner.plan(
                        true, null, null, needKbRoute(RouteDecision.TARGET_AGENT, false));
        assertFalse(plan.need());
        assertEquals(RouteDecision.KB_NONE, plan.kind());
    }

    @Test
    @DisplayName("L0 强制检索覆盖模型的 need_kb=false")
    void l0ForceWins() {
        KbRoutePlanner.Plan plan =
                KbRoutePlanner.plan(
                        false,
                        forced(KbRoutingPolicy.Decision.FORCE_EXPLICIT),
                        null,
                        needKbRoute(RouteDecision.TARGET_PIPELINE, false));
        assertTrue(plan.need());
        assertEquals(RouteDecision.KB_READ, plan.kind());
        assertEquals(RouteDecision.KB_SOURCE_FORCE, plan.source());
    }

    @Test
    @DisplayName("模型输出 need_kb=true 时按 read 检索")
    void routerNeedKb() {
        KbRoutePlanner.Plan plan =
                KbRoutePlanner.plan(
                        false, null, null, needKbRoute(RouteDecision.TARGET_AGENT, true));
        assertTrue(plan.need());
        assertEquals(RouteDecision.KB_READ, plan.kind());
        assertEquals(RouteDecision.KB_SOURCE_ROUTER, plan.source());
    }

    @Test
    @DisplayName("admin 只走管理智能体，不触发检索")
    void adminKindDoesNotSearch() {
        RouteDecision route =
                new RouteDecision(
                        RouteDecision.TARGET_AGENT,
                        kbAgent(false),
                        "建库",
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
    @DisplayName("路由未启用或未生效时退回 L0 / 静态链路")
    void routerFailureKeepsNoSearch() {
        assertFalse(KbRoutePlanner.plan(false, null, null, null).need());
        KbRoutePlanner.Plan failed =
                KbRoutePlanner.plan(false, null, null, RouteDecision.fallback("超时"));
        assertFalse(failed.need());
        assertEquals(RouteDecision.KB_SOURCE_FALLBACK, failed.source());
    }

    @Test
    @DisplayName("绑定流程不含知识库节点时，need_kb 会放弃该流程")
    void nonKbFlowIsDroppedWhenSearchRequired() {
        KbRoutePlanner.Plan plan =
                KbRoutePlanner.plan(
                        false, null, null, needKbRoute(RouteDecision.TARGET_PIPELINE, true));
        assertTrue(plan.need());
        assertFalse(PayrollChatService.flowUsable(plan.need(), false));
        assertTrue(PayrollChatService.flowUsable(plan.need(), true));
        assertTrue(PayrollChatService.flowUsable(false, false));
    }

    private static KbRoutingPolicy.Result forced(KbRoutingPolicy.Decision decision) {
        return new KbRoutingPolicy.Result(decision, KbRoutingPolicy.Source.EXPLICIT, false);
    }

    @Test
    @DisplayName("模型选中的单智能体没有 kb:read 时显式改道")
    void incompatibleAgentIsRerouted() {
        RouteDecision withoutKb =
                new RouteDecision(
                        RouteDecision.TARGET_AGENT,
                        kbAgent(false),
                        "制度问答",
                        0.9,
                        null,
                        true,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER);
        RouteDecision withKb =
                new RouteDecision(
                        RouteDecision.TARGET_AGENT,
                        kbAgent(true),
                        "制度问答",
                        0.9,
                        null,
                        true,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER);
        assertTrue(PayrollChatService.needsKbReroute(true, withoutKb));
        assertFalse(PayrollChatService.needsKbReroute(true, withKb));
        assertFalse(PayrollChatService.needsKbReroute(false, withoutKb));
        assertFalse(
                PayrollChatService.needsKbReroute(
                        true, needKbRoute(RouteDecision.TARGET_TEAM, true)));
    }

    @Test
    @DisplayName("旧 routing_json 无 kb 字段可加载，新快照能还原 kb")
    void chatRoutingJsonCompatibility() {
        ChatRouting legacy =
                JsonSupport.fromJson("{\"path\":\"AGENT\",\"persona\":\"agent:x\"}", ChatRouting.class);
        assertEquals(ChatRouting.AGENT, legacy.path());
        assertNull(legacy.kb());

        ChatRouting current =
                JsonSupport.fromJson(
                        "{\"path\":\"AGENT\",\"kb\":{\"need\":true,\"kind\":\"read\","
                                + "\"source\":\"router\",\"scope\":\"session\",\"reroute\":true}}",
                        ChatRouting.class);
        assertTrue(current.kb().need());
        assertEquals("read", current.kb().kind());
        assertEquals("router", current.kb().source());
        assertEquals("session", current.kb().scope());
        assertTrue(current.kb().reroute());
    }

    private static RouteDecision needKbRoute(String target, boolean needKb) {
        return new RouteDecision(
                target,
                null,
                "",
                0.9,
                null,
                needKb,
                needKb ? RouteDecision.KB_READ : RouteDecision.KB_NONE,
                RouteDecision.KB_SOURCE_ROUTER);
    }

    private static AgentDefinition kbAgent(boolean kbCapable) {
        return new AgentDefinition(
                "policy-001",
                "制度专家",
                null,
                "制度问答",
                List.of(kbCapable ? "kb" : "薪酬"),
                List.of("policy-knowledge"),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null);
    }
}
