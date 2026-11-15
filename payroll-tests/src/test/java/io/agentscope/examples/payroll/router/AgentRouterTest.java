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

    @Test
    void parsesKbDecision() {
        AgentRouter router = routerReturning(
                "{\"target\":\"agent\",\"agent_id\":\"policy-001\",\"need_kb\":true,"
                        + "\"kb_kind\":\"read\",\"reason\":\"制度问答\",\"confidence\":0.9}");
        RouteDecision decision =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        assertTrue(decision.routed());
        assertTrue(decision.needKb());
        assertEquals(RouteDecision.KB_READ, decision.kbKind());
        assertEquals(RouteDecision.KB_SOURCE_ROUTER, decision.kbSource());
    }

    @Test
    void missingKbFieldsDefaultToNoSearch() {
        RouteDecision decision =
                routerReturning(
                                "{\"target\":\"pipeline\",\"agent_id\":\"\",\"reason\":\"x\","
                                        + "\"confidence\":0.9}")
                        .decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertTrue(decision.routed());
        assertFalse(decision.needKb());
        assertEquals(RouteDecision.KB_NONE, decision.kbKind());
    }

    @Test
    void needKbTrueWithoutKindDefaultsToRead() {
        RouteDecision decision =
                routerReturning(
                                "{\"target\":\"pipeline\",\"agent_id\":\"\",\"need_kb\":true,"
                                        + "\"reason\":\"x\",\"confidence\":0.9}")
                        .decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertTrue(decision.needKb());
        assertEquals(RouteDecision.KB_READ, decision.kbKind());
    }

    @Test
    void illegalKbFieldsDoNotFailRouting() {
        RouteDecision decision =
                routerReturning(
                                "{\"target\":\"pipeline\",\"agent_id\":\"\",\"need_kb\":\"yes\","
                                        + "\"kb_kind\":\"whatever\",\"reason\":\"x\",\"confidence\":0.9}")
                        .decide("问题", List.of(POLICY_AGENT), false, "1:1");
        assertTrue(decision.routed());
        assertFalse(decision.needKb());
        assertEquals(RouteDecision.KB_NONE, decision.kbKind());
    }

    @Test
    void parsesAdminKind() {
        RouteDecision decision =
                routerReturning(
                                "{\"target\":\"agent\",\"agent_id\":\"policy-001\",\"need_kb\":false,"
                                        + "\"kb_kind\":\"admin\",\"reason\":\"建库\",\"confidence\":0.9}")
                        .decide("新建知识库", List.of(POLICY_AGENT), false, "1:1");
        assertEquals(RouteDecision.KB_ADMIN, decision.kbKind());
        assertFalse(decision.needKb());
    }

    @Test
    void payrollInstructionStaysByteIdentical() {
        String expected =
                """
                你是薪酬管理台的智能体路由决策器。根据用户问题在三个执行目标中选择一个：
                - "agent"：单个专家智能体直接作答，必须给出候选列表中的 agent_id；
                - "team"：由多个智能体动态组队完成，仅当“团队可用：是”时可选；
                - "pipeline"：内置三段流水线（计算 → 验证 → 核算），始终可选，作为默认。

                只输出一个合法 JSON 对象，不要 Markdown 代码围栏，不要解释：
                {"target":"agent|team|pipeline","agent_id":"候选 id 或空串","reason":"一句话中文理由","confidence":0.0}

                判断规则：
                1. 工资计算、批量核算、工资条明细等需要严格核算的问题，优先 "pipeline"，需要多角色协作且团队可用时选 "team"；
                2. 单一专家即可胜任的制度问答、政策解释、数据查询，选 "agent" 并给出最匹配的 agent_id；
                3. 知识库类问题（建库、解析/分块/检索配置、目录与成员权限、入库任务）不要选 "pipeline"；
                   其中建库、保存配置、加/移成员、移动目录、重建索引等写操作只能选 "agent"，
                   并优先 tags 含 kb 或 kb-config 的候选智能体；只读问答同样优先该智能体；
                4. 无法确定或候选都不匹配时选 "pipeline"，并给出较低的 confidence。

                候选智能体：
                - （无可用单智能体）
                团队可用：是
                """;
        assertEquals(expected, AgentRouter.instruction(List.of(), true, true));
        assertTrue(AgentRouter.instruction(List.of(), true, false).contains("need_kb"));
        assertTrue(AgentRouter.instruction(List.of(), true, false).contains("kb_kind"));
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
