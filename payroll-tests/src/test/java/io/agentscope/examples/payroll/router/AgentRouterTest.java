package io.agentscope.examples.payroll.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRouterTest {

    private static final AgentDefinition POLICY_AGENT =
            agent("policy-001", "制度专家", AgentDefinition.STATUS_ONLINE, "policy");
    private static final AgentDefinition QUERY_AGENT =
            agent("query-001", "查询专家", AgentDefinition.STATUS_ONLINE, "query");
    private static final AgentDefinition OFFLINE_AGENT =
            agent("offline-001", "下架专家", AgentDefinition.STATUS_OFFLINE, "policy");
    private static final AgentDefinition CALCULATOR_AGENT =
            agent("calc-001", "计算专家", AgentDefinition.STATUS_ONLINE, RoleSkills.CALCULATOR);

    @Test
    void candidatesExcludeOfflineAndPipelineAgents() {
        List<AgentDefinition> candidates =
                AgentRouter.candidates(
                        List.of(POLICY_AGENT, QUERY_AGENT, OFFLINE_AGENT, CALCULATOR_AGENT));
        assertEquals(List.of("policy-001", "query-001"), candidates.stream().map(AgentDefinition::id).toList());
    }

    @Test
    void routesToCandidateAgent() {
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) ->
                                "{\"target\":\"agent\",\"agent_id\":\"policy-001\","
                                        + "\"reason\":\"制度问答\",\"confidence\":0.86}",
                        true,
                        0.55D);
        RouteDecision decision =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        assertNotNull(decision);
        assertTrue(decision.routed());
        assertEquals(RouteDecision.TARGET_AGENT, decision.target());
        assertEquals("policy-001", decision.agent().id());
        assertEquals("router:agent:policy-001", decision.signature());
        assertTrue(decision.note().contains("0.86"));
    }

    @Test
    void instructsModelWithCandidatesAndTeamAvailability() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            prompt.set(instruction);
                            return "{\"target\":\"pipeline\",\"agent_id\":\"\","
                                    + "\"reason\":\"需要核算\",\"confidence\":0.9}";
                        },
                        true,
                        0.55D);
        router.decide("算一下 11 月工资", List.of(POLICY_AGENT), true, "1:1");
        assertTrue(prompt.get().contains("policy-001 | 制度专家"));
        assertTrue(prompt.get().contains("团队可用：是"));
    }

    @Test
    void rejectsUnknownAgentId() {
        AgentRouter router = routerReturning(
                "{\"target\":\"agent\",\"agent_id\":\"ghost\",\"reason\":\"x\",\"confidence\":0.9}");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("ghost"));
    }

    @Test
    void rejectsTeamWhenUnavailable() {
        AgentRouter router = routerReturning(
                "{\"target\":\"team\",\"agent_id\":\"\",\"reason\":\"协作\",\"confidence\":0.9}");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("动态团队"));
    }

    @Test
    void acceptsTeamWhenAvailable() {
        AgentRouter router = routerReturning(
                "{\"target\":\"team\",\"agent_id\":\"\",\"reason\":\"协作\",\"confidence\":0.9}");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), true, "1:1");
        assertTrue(decision.routed());
        assertEquals(RouteDecision.TARGET_TEAM, decision.target());
        assertEquals("router:team", decision.signature());
    }

    @Test
    void rejectsLowConfidence() {
        AgentRouter router = routerReturning(
                "{\"target\":\"agent\",\"agent_id\":\"policy-001\",\"reason\":\"x\",\"confidence\":0.3}");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("低于阈值"));
    }

    @Test
    void fallsBackOnInvalidJson() {
        AgentRouter router = routerReturning("我不知道");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("JSON"));
    }

    @Test
    void fallsBackWhenModelReturnsNull() {
        AgentRouter router = new AgentRouter((instruction, question, key) -> null, true, 0.55D);
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("不可用"));
    }

    @Test
    void fallsBackWhenModelThrows() {
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            throw new IllegalStateException("模型超时");
                        },
                        true,
                        0.55D);
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("模型超时"));
    }

    @Test
    void disabledRouterDoesNothing() {
        AgentRouter router = AgentRouter.disabled();
        assertFalse(router.enabled());
        assertNull(router.decide("问题", List.of(POLICY_AGENT), true, "1:1"));
    }

    @Test
    void rejectsIllegalTarget() {
        AgentRouter router = routerReturning(
                "{\"target\":\"workflow\",\"agent_id\":\"\",\"reason\":\"x\",\"confidence\":0.9}");
        RouteDecision decision =
                router.decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertFalse(decision.routed());
        assertTrue(decision.failureReason().contains("workflow"));
    }

    private static AgentRouter routerReturning(String json) {
        return new AgentRouter((instruction, question, key) -> json, true, 0.55D);
    }

    private static AgentDefinition agent(String id, String name, String status, String skillId) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentDefinition(
                id,
                name,
                "🤖",
                name + "的职责说明",
                List.of("测试"),
                List.of(skillId),
                AgentDefinition.VISIBILITY_PUBLIC,
                status,
                "tester",
                now,
                now);
    }
}
