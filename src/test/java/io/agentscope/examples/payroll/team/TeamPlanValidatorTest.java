package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamPlanValidatorTest {

    @Test
    @DisplayName("按模板实例化的计划通过全部校验")
    void acceptsTemplatePlan() {
        CapabilityIndex index = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());
        PlanValidation result =
                new PlanValidator(index)
                        .validate(validPlan(), TeamTestFixtures.calculationIntent());

        assertTrue(result.valid(), result::summary);
    }

    @Test
    @DisplayName("写操作、私有、下架与未知智能体都被拒绝")
    void rejectsIneligibleAgents() {
        CapabilityIndex catalog = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());
        PlanValidator validator = new PlanValidator(catalog);
        TeamIntent write =
                new TeamIntent(
                        TeamIntent.TASK_CALCULATE,
                        List.of("EMP001"),
                        List.of(),
                        List.of(),
                        "2026-10",
                        TeamIntent.RISK_HIGH,
                        true,
                        true,
                        List.of(TeamIntent.CAP_PAYROLL_CALC, TeamIntent.CAP_PAYROLL_VALIDATE),
                        0.9D,
                        "确认发薪");
        assertTrue(codes(validator.validate(validPlan(), write)).contains("WRITE_ACTION"));

        CapabilityIndex privateIndex =
                CapabilityIndex.from(
                        List.of(
                                TeamTestFixtures.agent(
                                        "calc-agent",
                                        "calc-agent",
                                        "payroll-calculator",
                                        AgentDefinition.VISIBILITY_PRIVATE,
                                        AgentDefinition.STATUS_ONLINE,
                                        ""),
                                TeamTestFixtures.agent("val-agent", "payroll-validator")));
        assertTrue(
                codes(new PlanValidator(privateIndex)
                                .validate(validPlan(), TeamTestFixtures.calculationIntent()))
                        .contains("AGENT_PRIVATE"));

        CapabilityIndex offlineIndex =
                CapabilityIndex.from(
                        List.of(
                                TeamTestFixtures.agent(
                                        "calc-agent",
                                        "calc-agent",
                                        "payroll-calculator",
                                        AgentDefinition.VISIBILITY_PUBLIC,
                                        AgentDefinition.STATUS_OFFLINE,
                                        ""),
                                TeamTestFixtures.agent("val-agent", "payroll-validator")));
        assertTrue(
                codes(new PlanValidator(offlineIndex)
                                .validate(validPlan(), TeamTestFixtures.calculationIntent()))
                        .contains("AGENT_OFFLINE"));

        CapabilityIndex normalIndex = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());
        TeamPlan ghost =
                TeamPlan.instantiate(
                        "ghost",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "ghost-agent",
                                TeamRole.VALIDATOR, "val-agent"),
                        "",
                        List.of());
        assertTrue(
                codes(new PlanValidator(normalIndex)
                                .validate(ghost, TeamTestFixtures.calculationIntent()))
                        .contains("AGENT_UNKNOWN"));
    }

    @Test
    @DisplayName("VALIDATOR 必须位于 CALCULATOR 下游")
    void rejectsValidatorUpstreamOfCalculator() {
        TeamPlan plan =
                new TeamPlan(
                        "order",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE.id(),
                        List.of(
                                TeamPlan.PlanNode.start("start"),
                                TeamPlan.PlanNode.agent("n1", TeamRole.VALIDATOR, "val-agent", "①"),
                                TeamPlan.PlanNode.agent("n2", TeamRole.CALCULATOR, "calc-agent", "②"),
                                TeamPlan.PlanNode.end("end")),
                        List.of(
                                new TeamPlan.PlanEdge("e1", "start", "n1", null),
                                new TeamPlan.PlanEdge("e2", "n1", "n2", null),
                                new TeamPlan.PlanEdge("e3", "n2", "end", null)),
                        new LinkedHashMap<>(
                                Map.of(
                                        TeamRole.VALIDATOR.name(), "val-agent",
                                        TeamRole.CALCULATOR.name(), "calc-agent")),
                        "",
                        List.of());

        List<String> codes =
                codes(
                        new PlanValidator(CapabilityIndex.from(TeamTestFixtures.builtinCatalog()))
                                .validate(plan, TeamTestFixtures.calculationIntent()));
        assertTrue(codes.contains("VALIDATOR_ORDER"));
    }

    @Test
    @DisplayName("环、重复智能体与角色绑定不一致都会被识别")
    void rejectsCycleDuplicateAgentAndBindingMismatch() {
        CapabilityIndex index = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());
        PlanValidator validator = new PlanValidator(index);

        TeamPlan cycle =
                new TeamPlan(
                        "cycle",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE.id(),
                        List.of(
                                TeamPlan.PlanNode.start("start"),
                                TeamPlan.PlanNode.agent("n1", TeamRole.CALCULATOR, "calc-agent", "①"),
                                TeamPlan.PlanNode.agent("n2", TeamRole.VALIDATOR, "val-agent", "②"),
                                TeamPlan.PlanNode.end("end")),
                        List.of(
                                new TeamPlan.PlanEdge("e1", "start", "n1", null),
                                new TeamPlan.PlanEdge("e2", "n1", "n2", null),
                                new TeamPlan.PlanEdge("e3", "n2", "end", null),
                                new TeamPlan.PlanEdge("e4", "end", "n1", null)),
                        new LinkedHashMap<>(
                                Map.of(
                                        TeamRole.CALCULATOR.name(), "calc-agent",
                                        TeamRole.VALIDATOR.name(), "val-agent")),
                        "",
                        List.of());
        assertTrue(codes(validator.validate(cycle, TeamTestFixtures.calculationIntent())).contains("CYCLE"));

        TeamPlan duplicate =
                TeamPlan.instantiate(
                        "duplicate",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "calc-agent",
                                TeamRole.VALIDATOR, "calc-agent"),
                        "",
                        List.of());
        assertTrue(
                codes(validator.validate(duplicate, TeamTestFixtures.calculationIntent()))
                        .contains("DUPLICATE_AGENT"));

        TeamPlan valid = validPlan();
        TeamPlan mismatch =
                new TeamPlan(
                        valid.planId(),
                        valid.taskType(),
                        valid.templateId(),
                        valid.nodes(),
                        valid.edges(),
                        new LinkedHashMap<>(
                                Map.of(
                                        TeamRole.CALCULATOR.name(), "calc-agent",
                                        TeamRole.VALIDATOR.name(), "other-agent")),
                        valid.rationale(),
                        valid.warnings());
        assertTrue(
                codes(validator.validate(mismatch, TeamTestFixtures.calculationIntent()))
                        .contains("ROLE_BINDING_MISMATCH"));
    }

    @Test
    @DisplayName("缺少复核角色或校验节点无法覆盖强制槽位")
    void rejectsMissingValidator() {
        TeamPlan plan =
                new TeamPlan(
                        "missing",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE.id(),
                        List.of(
                                TeamPlan.PlanNode.start("start"),
                                TeamPlan.PlanNode.agent("n1", TeamRole.CALCULATOR, "calc-agent", "①"),
                                TeamPlan.PlanNode.end("end")),
                        List.of(
                                new TeamPlan.PlanEdge("e1", "start", "n1", null),
                                new TeamPlan.PlanEdge("e2", "n1", "end", null)),
                        Map.of(TeamRole.CALCULATOR.name(), "calc-agent"),
                        "",
                        List.of());

        List<String> codes =
                codes(
                        new PlanValidator(CapabilityIndex.from(TeamTestFixtures.builtinCatalog()))
                                .validate(plan, TeamTestFixtures.calculationIntent()));
        assertTrue(codes.contains("MANDATORY_ROLE_MISSING"));
        assertTrue(codes.contains("MISSING_VALIDATION"));
    }

    @Test
    @DisplayName("空计划与未知模板直接失败")
    void rejectsEmptyPlanAndUnknownTemplate() {
        PlanValidator validator = new PlanValidator(CapabilityIndex.from(List.of()));
        assertFalse(validator.validate(null, TeamTestFixtures.calculationIntent()).valid());

        TeamPlan empty =
                new TeamPlan("empty", TeamIntent.TASK_CALCULATE, "NOPE", List.of(), List.of(), Map.of(), "", List.of());
        List<String> codes = codes(validator.validate(empty, TeamTestFixtures.calculationIntent()));
        assertTrue(codes.contains("UNKNOWN_TEMPLATE"));
        assertTrue(codes.contains("EMPTY_PLAN"));
    }

    private static TeamPlan validPlan() {
        return TeamPlan.instantiate(
                "valid",
                TeamIntent.TASK_CALCULATE,
                TeamTemplate.CALC_VALIDATE,
                Map.of(
                        TeamRole.CALCULATOR, "calc-agent",
                        TeamRole.VALIDATOR, "val-agent"),
                "测试",
                List.of());
    }

    private static List<String> codes(PlanValidation validation) {
        return validation.errors().stream().map(PlanError::code).toList();
    }
}
