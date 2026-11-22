package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.Citation;
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

    @Test
    @DisplayName("B0：执行者开关打开后不再因 need_kb 改道，关闭时保留旧语义")
    void executorFlagDisablesReroute() {
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
        assertTrue(PayrollChatService.shouldRerouteForKb(false, true, withoutKb));
        assertFalse(PayrollChatService.shouldRerouteForKb(true, true, withoutKb));
        assertFalse(PayrollChatService.shouldRerouteForKb(true, false, withoutKb));
    }

    @Test
    @DisplayName("B0：KB 兜底仅静态流水线与强制检索且缺 kb:read 时生效")
    void kbFallbackRules() {
        // 开关关闭：保持旧行为（流水线或无 kb:read 的智能体都兜底）
        assertTrue(PayrollChatService.shouldUseKbFallback(false, true, false, false));
        assertTrue(PayrollChatService.shouldUseKbFallback(false, false, false, false));
        assertFalse(PayrollChatService.shouldUseKbFallback(false, false, true, false));
        // 开关打开：流水线始终兜底；普通智能体即使无 kb:read 也保留执行者
        assertTrue(PayrollChatService.shouldUseKbFallback(true, true, false, false));
        assertFalse(PayrollChatService.shouldUseKbFallback(true, false, false, false));
        // 仅强制检索才换 KB 智能体，且执行者本身有 kb:read 时不换
        assertTrue(PayrollChatService.shouldUseKbFallback(true, false, false, true));
        assertFalse(PayrollChatService.shouldUseKbFallback(true, false, true, true));
    }

    @Test
    @DisplayName("B1：need_kb 退回流水线时保留配置执行者，开关关闭或强制检索不改道")
    void keepPreferredExecutorForKb() {
        RouteDecision pipelineKb =
                new RouteDecision(
                        RouteDecision.TARGET_PIPELINE,
                        null,
                        "需要检索知识库",
                        0.9,
                        null,
                        true,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER);
        assertTrue(
                PayrollChatService.keepPreferredExecutorForKb(
                        true,
                        true,
                        true,
                        false,
                        false,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        false,
                        true,
                        true,
                        false,
                        false,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true,
                        false,
                        true,
                        false,
                        false,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true,
                        true,
                        true,
                        true,
                        false,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true,
                        true,
                        true,
                        false,
                        true,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true,
                        true,
                        true,
                        false,
                        false,
                        pipelineKb,
                        RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_FORCE));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true, true, false, false, false, pipelineKb, RouteDecision.KB_NONE,
                        RouteDecision.KB_SOURCE_ROUTER));
        assertFalse(
                PayrollChatService.keepPreferredExecutorForKb(
                        true, true, true, false, false, null, RouteDecision.KB_READ,
                        RouteDecision.KB_SOURCE_ROUTER));
    }

    @Test
    @DisplayName("B2：只保留正文实际引用的角标，且不重编号")
    void citationFilterKeepsUsedIndices() {
        List<Citation> injected = List.of(citation(1, "甲"), citation(2, "乙"), citation(3, "丙"));
        List<Citation> filtered =
                PayrollChatService.filterCitations(injected, "年假 5 天[1]，婚假 10 天[3]，另有[9]。");
        assertEquals(List.of(1, 3), filtered.stream().map(Citation::index).toList());
        assertEquals("甲", filtered.get(0).snippet());
        assertEquals("丙", filtered.get(1).snippet());
        assertTrue(PayrollChatService.filterCitations(injected, "没有任何角标").isEmpty());
        assertEquals(
                List.of(1),
                PayrollChatService.filterCitations(injected, "见[01]").stream()
                        .map(Citation::index)
                        .toList());
        assertTrue(PayrollChatService.filterCitations(List.of(), "[1]").isEmpty());
    }

    @Test
    @DisplayName("预取材料快照随 routing_json 往返，旧快照无 context 时仍可加载")
    void kbContextJsonRoundTrip() {
        ChatRouting current =
                JsonSupport.fromJson(
                        "{\"path\":\"AGENT\",\"kb\":{\"need\":true,\"kind\":\"read\","
                                + "\"source\":\"router\",\"scope\":\"session\",\"reroute\":false,"
                                + "\"context\":{\"attempted\":true,\"injected\":true,\"hits\":2,"
                                + "\"chars\":320,\"dropped\":1,\"status\":\"ok\",\"cited\":[1,3]}}}",
                        ChatRouting.class);
        ChatRouting.Kb.Context context = current.kb().context();
        assertTrue(context.attempted());
        assertTrue(context.injected());
        assertEquals(2, context.hits());
        assertEquals(320, context.chars());
        assertEquals(1, context.dropped());
        assertEquals("ok", context.status());
        assertEquals(List.of(1, 3), context.cited());

        ChatRouting.Kb legacy = new ChatRouting.Kb(true, "read", "router", "all", false);
        assertNull(legacy.context());
    }

    private static Citation citation(int index, String snippet) {
        return new Citation(
                index,
                "K" + index,
                1L,
                "制度库",
                10L + index,
                "员工手册",
                100L + index,
                3,
                "休假",
                0.9,
                List.of("bm25"),
                snippet);
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
