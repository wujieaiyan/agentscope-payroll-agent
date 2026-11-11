package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamPlanGeneratorTest {

    @Test
    @DisplayName("模型不可用时按 Top-1 确定性组队，并记录冷启动警告")
    void deterministicPicksTopCandidates() {
        List<AgentDefinition> catalog = TeamTestFixtures.builtinCatalog();
        TeamPlanGenerator generator =
                new TeamPlanGenerator(TeamLlmClient.disabled(), new PlanValidator(CapabilityIndex.from(catalog)));
        TeamShortlist shortlist =
                shortlist(
                        Map.of(
                                TeamRole.CALCULATOR,
                                List.of(
                                        TeamTestFixtures.candidate(
                                                "calc-agent",
                                                TeamRole.CALCULATOR,
                                                Set.of(TeamIntent.CAP_PAYROLL_CALC),
                                                0.9D,
                                                0,
                                                true,
                                                true)),
                                TeamRole.VALIDATOR,
                                List.of(
                                        TeamTestFixtures.candidate(
                                                "val-agent",
                                                TeamRole.VALIDATOR,
                                                Set.of(TeamIntent.CAP_PAYROLL_VALIDATE),
                                                0.85D,
                                                0,
                                                true,
                                                true))));

        PlanResult result =
                generator.generate(TeamTestFixtures.calculationIntent(), shortlist, "plan-1");

        assertTrue(result.ok(), () -> String.valueOf(result.errors()));
        TeamPlan plan = result.plan();
        assertEquals("calc-agent", plan.roleToAgent().get(TeamRole.CALCULATOR.name()));
        assertEquals("val-agent", plan.roleToAgent().get(TeamRole.VALIDATOR.name()));
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("冷启动")));
        assertEquals("按评分 Top-1 组队", plan.rationale());
    }

    @Test
    @DisplayName("同一智能体在两个角色都排第一时去重到下一个候选")
    void deduplicatesAgentsAcrossRoles() {
        List<AgentDefinition> catalog = TeamTestFixtures.builtinCatalog();
        TeamPlanGenerator generator =
                new TeamPlanGenerator(TeamLlmClient.disabled(), new PlanValidator(CapabilityIndex.from(catalog)));
        TeamShortlist shortlist =
                shortlist(
                        Map.of(
                                TeamRole.CALCULATOR,
                                List.of(
                                        TeamTestFixtures.candidate(
                                                "calc-agent",
                                                TeamRole.CALCULATOR,
                                                Set.of(TeamIntent.CAP_PAYROLL_CALC),
                                                0.9D,
                                                3,
                                                false,
                                                true)),
                                TeamRole.VALIDATOR,
                                List.of(
                                        TeamTestFixtures.candidate(
                                                "calc-agent",
                                                TeamRole.VALIDATOR,
                                                Set.of(TeamIntent.CAP_PAYROLL_CALC),
                                                0.9D,
                                                3,
                                                false,
                                                true),
                                        TeamTestFixtures.candidate(
                                                "val-agent",
                                                TeamRole.VALIDATOR,
                                                Set.of(TeamIntent.CAP_PAYROLL_VALIDATE),
                                                0.8D,
                                                3,
                                                false,
                                                true))));

        PlanResult result =
                generator.generate(TeamTestFixtures.calculationIntent(), shortlist, "plan-2");

        assertTrue(result.ok(), () -> String.valueOf(result.errors()));
        assertEquals("calc-agent", result.plan().roleToAgent().get(TeamRole.CALCULATOR.name()));
        assertEquals("val-agent", result.plan().roleToAgent().get(TeamRole.VALIDATOR.name()));
    }

    @Test
    @DisplayName("去重后没有候选、意图不合格或模板回退时返回回退原因")
    void fallsBackWhenPlanCannotBeBuilt() {
        List<AgentDefinition> onlyCalculator = List.of(TeamTestFixtures.agent("calc-agent", "payroll-calculator"));
        TeamPlanGenerator generator =
                new TeamPlanGenerator(
                        TeamLlmClient.disabled(),
                        new PlanValidator(CapabilityIndex.from(onlyCalculator)));
        AgentCandidate candidate =
                TeamTestFixtures.candidate(
                        "calc-agent",
                        TeamRole.CALCULATOR,
                        Set.of(TeamIntent.CAP_PAYROLL_CALC),
                        0.9D,
                        2,
                        false,
                        true);
        TeamShortlist duplicate =
                shortlist(
                        Map.of(
                                TeamRole.CALCULATOR, List.of(candidate),
                                TeamRole.VALIDATOR, List.of(candidate)));

        PlanResult result =
                generator.generate(TeamTestFixtures.calculationIntent(), duplicate, "plan-3");
        assertFalse(result.ok());
        assertTrue(result.fallbackReason().startsWith("角色无可用候选"));

        PlanResult ineligible =
                generator.generate(
                        new TeamIntent(
                                TeamIntent.TASK_OTHER,
                                List.of(),
                                List.of(),
                                List.of(),
                                "",
                                TeamIntent.RISK_LOW,
                                false,
                                false,
                                List.of(),
                                0.2D,
                                "你好"),
                        TeamTemplate.CALC_VALIDATE,
                        duplicate,
                        "plan-4");
        assertEquals("意图不满足动态组队条件", ineligible.fallbackReason());

        PlanResult fallback =
                generator.generate(
                        TeamTestFixtures.calculationIntent(),
                        TeamTemplate.FALLBACK,
                        duplicate,
                        "plan-5");
        assertEquals("没有匹配的团队模板", fallback.fallbackReason());
    }

    @Test
    @DisplayName("合格计划编译为可执行流程图，节点与连线来自模板")
    void compilesPlanToFlow() {
        TeamPlan plan =
                TeamPlan.instantiate(
                        "plan-6",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "calc-agent",
                                TeamRole.VALIDATOR, "val-agent"),
                        "",
                        List.of());

        FlowDefinition flow = TeamPlanCompiler.compile(plan, "dynamic-plan-6");

        assertEquals("dynamic-plan-6", flow.id());
        assertEquals("动态团队·核算 → 复核", flow.name());
        assertTrue(flow.description().contains("CALC_VALIDATE"));
        assertEquals("dynamic-team", flow.createdBy());
        assertEquals(4, flow.nodes().size());
        assertEquals(3, flow.edges().size());
        FlowNode calculator =
                flow.nodes().stream().filter(node -> "n1".equals(node.id())).findFirst().orElse(null);
        assertNotNull(calculator);
        assertEquals(FlowNode.TYPE_AGENT, calculator.type());
        assertEquals("calc-agent", calculator.agentId());
        assertEquals(190D, calculator.x(), 1e-9);
        FlowNode validator =
                flow.nodes().stream().filter(node -> "n2".equals(node.id())).findFirst().orElseThrow();
        assertEquals(380D, validator.x(), 1e-9);
    }

    @Test
    @DisplayName("模型回填的 agent_id 带注解或引号时先清洗再匹配")
    void cleansAnnotatedAgentId() {
        assertEquals(
                "payroll-calculator",
                TeamPlanGenerator.cleanAgentId(
                        "payroll-calculator(rank=0.864, objective=78.0, feedback=65.0, samples=0, cold_start)"));
        assertEquals("payroll-validator", TeamPlanGenerator.cleanAgentId("  payroll-validator（冷启动） "));
        assertEquals("payroll-calculator", TeamPlanGenerator.cleanAgentId("\"payroll-calculator\""));
        assertEquals("", TeamPlanGenerator.cleanAgentId(null));
    }

    private static TeamShortlist shortlist(Map<TeamRole, List<AgentCandidate>> byRole) {
        return new TeamShortlist(
                TeamTemplate.CALC_VALIDATE.id(), byRole, "{}", 30, false);
    }
}
