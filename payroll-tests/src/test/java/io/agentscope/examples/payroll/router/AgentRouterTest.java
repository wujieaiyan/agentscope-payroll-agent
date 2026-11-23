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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final AgentDefinition POETRY_AGENT =
            agent("poetry-001", "诗词专家", AgentDefinition.STATUS_ONLINE, "poetry");

    @Test
    void candidatesExcludeOfflineAndPipelineAgents() {
        List<AgentDefinition> candidates =
                AgentRouter.candidates(
                        List.of(POLICY_AGENT, QUERY_AGENT, OFFLINE_AGENT, CALCULATOR_AGENT));
        assertEquals(List.of("policy-001", "query-001"), candidates.stream().map(AgentDefinition::id).toList());
    }

    @Test
    void fallbackRoutesGreetingToGeneralAgentInsteadOfPipeline() {
        AgentDefinition chat = tagged("chat-001", "情感专家", "闲聊、情感陪伴与自我介绍", "情感");
        AgentDefinition picked =
                AgentRouter.fallbackAgent("你好", List.of(CALCULATOR_AGENT, chat, POETRY_AGENT), null);
        assertNotNull(picked);
        assertEquals("chat-001", picked.id());
    }

    @Test
    void fallbackRoutesCreativeRequestToPoetryAgent() {
        AgentDefinition chat = tagged("chat-001", "情感专家", "闲聊与情感陪伴", "情感");
        AgentDefinition picked =
                AgentRouter.fallbackAgent(
                        "写一首关于加班的诗", List.of(CALCULATOR_AGENT, chat, POETRY_AGENT), null);
        assertNotNull(picked);
        assertEquals("poetry-001", picked.id());
    }

    @Test
    void fallbackKeepsPreferredExecutor() {
        AgentDefinition chat = tagged("chat-001", "情感专家", "闲聊与情感陪伴", "情感");
        AgentDefinition picked =
                AgentRouter.fallbackAgent("你好", List.of(chat, POETRY_AGENT), POETRY_AGENT);
        assertNotNull(picked);
        assertEquals("poetry-001", picked.id());
    }

    @Test
    void fallbackReturnsNullWhenNoDomainMatches() {
        assertNull(
                AgentRouter.fallbackAgent(
                        "今天天气怎么样", List.of(CALCULATOR_AGENT, POLICY_AGENT), null));
        assertNull(AgentRouter.fallbackAgent("你好", List.of(CALCULATOR_AGENT), null));
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
    void treatsCandidateIdPlacedInTargetAsAgent() {
        RouteDecision decision =
                routerReturning(
                                "{\"target\":\"poetry-001\",\"agent_id\":\"\",\"reason\":\"创作\","
                                        + "\"confidence\":0.9}")
                        .decide("写一首成长的诗", List.of(POLICY_AGENT, POETRY_AGENT), false, "1:1");
        assertTrue(decision.routed());
        assertEquals("poetry-001", decision.agent().id());
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
                - "pipeline"：内置三段薪酬流水线（计算 → 验证 → 核算），只用于工资核算类问题。

                只输出一个合法 JSON 对象，不要 Markdown 代码围栏，不要解释：
                {"target":"agent|team|pipeline","agent_id":"候选 id 或空串","reason":"一句话中文理由","confidence":0.0}

                判断规则：
                1. 工资计算、批量核算、工资条明细等需要严格核算的问题，优先 "pipeline"，需要多角色协作且团队可用时选 "team"；
                2. 单一专家即可胜任的问题，选 "agent" 并给出最匹配的 agent_id；
                3. 问候、寒暄、自我介绍、能力咨询、对回答方式的追问等通用对话，选名称、描述或 tags
                   含闲聊、情感、通用、助理的候选智能体（如情感专家），不要选 "pipeline"；
                4. 知识库类问题（建库、解析/分块/检索配置、目录与成员权限、入库任务）不要选 "pipeline"；
                   其中建库、保存配置、加/移成员、移动目录、重建索引等写操作只能选 "agent"，
                   并优先 tags 含 kb 或 kb-config 的候选智能体；只读问答同样优先该智能体；
                5. 非薪酬问题只有候选都不匹配时才选 "pipeline"；薪酬问题无法确定时选 "pipeline"，
                   并给出较低的 confidence。

                候选智能体：
                - （无可用单智能体）
                团队可用：是
                """;
        assertEquals(expected, AgentRouter.instruction(List.of(), true, true));
        assertTrue(AgentRouter.instruction(List.of(), true, false).contains("need_kb"));
        assertTrue(AgentRouter.instruction(List.of(), true, false).contains("kb_kind"));
    }

    @Test
    void nonPayrollInstructionCoversCreativeWriting() {
        String prompt = AgentRouter.instruction(List.of(POLICY_AGENT), false, false);
        assertTrue(prompt.contains("创作类判定"));
        assertTrue(prompt.contains("不要因为句中出现“写”“创作”等字样就选办公文书专家"));
    }

    @Test
    void nonPayrollInstructionKeepsExecutorIndependentOfKbNeed() {
        String prompt = AgentRouter.instruction(List.of(POLICY_AGENT), false, false);
        assertTrue(prompt.contains("need_kb 只决定是否给执行者补充资料，不决定由谁回答"));
        assertTrue(prompt.contains("不要因为需要检索就改选 \"pipeline\""));

        String payroll = AgentRouter.instruction(List.of(POLICY_AGENT), true, true);
        assertFalse(payroll.contains("need_kb 只决定是否给执行者补充资料"));
    }

    @Test
    void creativeNoKbSectionOnlyWhenEnabledAndNonPayroll() {
        String enabled = AgentRouter.instruction(List.of(POLICY_AGENT), false, false, null, true);
        assertTrue(enabled.contains("创作类检索判定"));
        assertTrue(enabled.contains("need_kb=false"));

        String disabled = AgentRouter.instruction(List.of(POLICY_AGENT), false, false, null, false);
        assertFalse(disabled.contains("创作类检索判定"));

        String payroll = AgentRouter.instruction(List.of(POLICY_AGENT), true, true, null, true);
        assertFalse(payroll.contains("创作类检索判定"));
    }

    @Test
    void creativeFlagReachesDecideInstruction() {
        java.util.concurrent.atomic.AtomicReference<String> prompt =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            prompt.set(instruction);
                            return "{\"target\":\"pipeline\",\"agent_id\":\"\","
                                    + "\"reason\":\"创作\",\"confidence\":0.9}";
                        },
                        true,
                        0.55D,
                        true);
        router.decide("写一首关于饺子的诗", List.of(POETRY_AGENT), false, "1:1");
        assertTrue(prompt.get().contains("创作类检索判定"));
    }

    @Test
    void activeAgentIsPreferredInNonPayrollInstruction() {
        String prompt =
                AgentRouter.instruction(
                        List.of(POLICY_AGENT, POETRY_AGENT), false, false, POETRY_AGENT);
        assertTrue(prompt.contains("当前活跃智能体（默认首选）：poetry-001 | 诗词专家"));
        assertTrue(prompt.contains("默认保留当前活跃智能体"));
    }

    @Test
    void instructionOmitsPreferenceWithoutActiveAgent() {
        String prompt = AgentRouter.instruction(List.of(POLICY_AGENT), false, false);
        assertFalse(prompt.contains("当前活跃智能体（默认首选）"));
    }

    @Test
    void payrollInstructionIgnoresActiveAgentPreference() {
        String plain = AgentRouter.instruction(List.of(POLICY_AGENT), true, true);
        String withPreference =
                AgentRouter.instruction(List.of(POLICY_AGENT), true, true, POETRY_AGENT);
        assertEquals(plain, withPreference);
    }

    @Test
    void decidePassesPreferredAgentToModel() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            prompt.set(instruction);
                            return "{\"target\":\"agent\",\"agent_id\":\"poetry-001\","
                                    + "\"reason\":\"文学创作\",\"confidence\":0.92}";
                        },
                        true,
                        0.55D);
        RouteDecision decision =
                router.decide(
                        "写一首成长的诗",
                        List.of(POLICY_AGENT, POETRY_AGENT),
                        false,
                        "1:1",
                        false,
                        POETRY_AGENT);
        assertTrue(decision.routed());
        assertEquals("poetry-001", decision.agent().id());
        assertTrue(prompt.get().contains("当前活跃智能体（默认首选）：poetry-001"));
    }

    @Test
    void priorTurnKeepsSameAgentForFollowUp() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            prompt.set(instruction);
                            return "{\"target\":\"agent\",\"agent_id\":\"poetry-001\","
                                    + "\"reason\":\"延续上一轮诗作\",\"confidence\":0.9}";
                        },
                        true,
                        0.55D);
        RouteDecision decision =
                router.decide(
                        "不能为公司，只为苟活。",
                        List.of(POLICY_AGENT, POETRY_AGENT),
                        false,
                        "1:1",
                        false,
                        null,
                        new AgentRouter.Prior(
                                "poetry-001", "写一首关于加班的诗词", "灯火通明夜未央，员工挥汗待晨光。"));
        assertEquals("poetry-001", decision.agent().id());
        assertTrue(prompt.get().contains("多轮延续"));
        assertTrue(prompt.get().contains("上一轮用户问题：写一首关于加班的诗词"));
        assertTrue(prompt.get().contains("上一轮执行者：poetry-001"));
        assertTrue(prompt.get().contains("灯火通明夜未央"));
        assertTrue(prompt.get().contains("当前活跃智能体（默认首选）：poetry-001"));
    }

    @Test
    void priorAgentOverridesAdminPreferredAgent() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            prompt.set(instruction);
                            return "{\"target\":\"agent\",\"agent_id\":\"poetry-001\","
                                    + "\"reason\":\"续写\",\"confidence\":0.9}";
                        },
                        true,
                        0.55D);
        router.decide(
                "再改一句，把重点放到家人身上",
                List.of(POLICY_AGENT, POETRY_AGENT),
                false,
                "1:1",
                false,
                POLICY_AGENT,
                new AgentRouter.Prior("poetry-001", "写一首关于加班的诗词", "灯火通明夜未央"));
        assertTrue(prompt.get().contains("当前活跃智能体（默认首选）：poetry-001"));
    }

    @Test
    void priorAgentIsIgnoredWhenNoLongerCandidate() {
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
        router.decide(
                "算一下 11 月工资",
                List.of(POLICY_AGENT),
                false,
                "1:1",
                false,
                POLICY_AGENT,
                new AgentRouter.Prior("poetry-001", "写一首诗", "灯火通明夜未央"));
        assertTrue(prompt.get().contains("当前活跃智能体（默认首选）：policy-001"));
        assertFalse(prompt.get().contains("多轮延续"));
    }

    @Test
    void payrollInstructionIgnoresPriorTurn() {
        String prompt =
                AgentRouter.instruction(
                        List.of(POLICY_AGENT),
                        false,
                        true,
                        null,
                        false,
                        new AgentRouter.Prior("poetry-001", "写一首诗", "灯火通明夜未央"));
        assertFalse(prompt.contains("多轮延续"));
    }

    @Test
    void identicalQuestionHitsRouteCache() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router = countingRouter(calls, 300L, 512);

        RouteDecision first =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        RouteDecision second =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");

        assertEquals(1, calls.get(), "相同问题第二次应命中缓存");
        assertTrue(first.routed());
        assertTrue(second.routed());
        assertEquals("policy-001", second.agent().id());
        assertNull(second.failureReason());
    }

    @Test
    void cacheDisabledCallsModelEveryTime() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            calls.incrementAndGet();
                            return routedPolicyJson();
                        },
                        true,
                        0.55D,
                        false,
                        false,
                        300L,
                        512);

        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");

        assertEquals(2, calls.get());
    }

    @Test
    void cacheExpiresAfterTtl() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router = countingRouter(calls, 1L, 512);

        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        Thread.sleep(1_100L);
        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");

        assertEquals(2, calls.get(), "TTL 过期后必须重新调用模型");
    }

    @Test
    void cacheHitRebindsAgentFromCurrentCandidates() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router = countingRouter(calls, 300L, 512);
        AgentDefinition fresh =
                agent("policy-001", "制度专家", AgentDefinition.STATUS_ONLINE, "policy");

        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        RouteDecision second =
                router.decide("年假政策是什么？", List.of(fresh), false, "1:1");

        assertEquals(1, calls.get());
        assertTrue(second.agent() == fresh, "命中后应返回当前候选中的实例");
    }

    @Test
    void cacheMissesWhenCandidateListChanges() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router = countingRouter(calls, 300L, 512);

        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        router.decide("年假政策是什么？", List.of(POLICY_AGENT, QUERY_AGENT), false, "1:1");

        assertEquals(2, calls.get());
    }

    @Test
    void cacheEvictsLeastRecentlyUsedEntry() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router = countingRouter(calls, 300L, 1);

        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        router.decide("病假怎么算？", List.of(POLICY_AGENT), false, "1:1");
        router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");

        assertEquals(3, calls.get(), "容量为 1 时旧条目应被淘汰");
    }

    @Test
    void fallbackDecisionsAreNotCached() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            calls.incrementAndGet();
                            return "我不知道";
                        },
                        true,
                        0.55D,
                        false,
                        true,
                        300L,
                        512);

        RouteDecision first =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");
        RouteDecision second =
                router.decide("年假政策是什么？", List.of(POLICY_AGENT), false, "1:1");

        assertFalse(first.routed());
        assertFalse(second.routed());
        assertEquals(2, calls.get(), "回退决策不应缓存");
    }

    @Test
    void teamDecisionsAreNotCached() {
        AtomicInteger calls = new AtomicInteger();
        AgentRouter router =
                new AgentRouter(
                        (instruction, question, key) -> {
                            calls.incrementAndGet();
                            return "{\"target\":\"team\",\"agent_id\":\"\","
                                    + "\"reason\":\"协作\",\"confidence\":0.9}";
                        },
                        true,
                        0.55D,
                        false,
                        true,
                        300L,
                        512);

        router.decide("问题", List.of(POLICY_AGENT), true, "1:1");
        router.decide("问题", List.of(POLICY_AGENT), true, "1:1");

        assertEquals(2, calls.get());
    }

    private static AgentRouter countingRouter(
            AtomicInteger calls, long ttlSeconds, int maxEntries) {
        return new AgentRouter(
                (instruction, question, key) -> {
                    calls.incrementAndGet();
                    return routedPolicyJson();
                },
                true,
                0.55D,
                false,
                true,
                ttlSeconds,
                maxEntries);
    }

    private static String routedPolicyJson() {
        return "{\"target\":\"agent\",\"agent_id\":\"policy-001\","
                + "\"reason\":\"制度问答\",\"confidence\":0.86}";
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

    private static AgentDefinition tagged(
            String id, String name, String description, String... tags) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentDefinition(
                id,
                name,
                "🤖",
                description,
                List.of(tags),
                List.of("test"),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                now,
                now);
    }
}
