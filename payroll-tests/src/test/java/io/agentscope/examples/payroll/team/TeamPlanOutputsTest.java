package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P0-4：计划节点携带产物与验收标准，模型可选的 steps 字段解析失败不影响组队。 */
class TeamPlanOutputsTest {

    private static final String PLAN_JSON =
            """
            {"roles":{"CALCULATOR":"calc-agent","VALIDATOR":"val-agent"},
             "steps":{
               "CALCULATOR":{"outputs":["核算明细"],"acceptance":["金额与政策一致","含逐项依据"]},
               "VALIDATOR":{"outputs":["复核结论"],"acceptance":["差异为 0 或给出说明"]}
             },
             "rationale":"按能力选择"}
            """;

    @Test
    @DisplayName("steps.outputs/acceptance 按角色解析")
    void parsesStepsByRole() {
        Map<TeamRole, List<String>> outputs = TeamPlanGenerator.parseSteps(PLAN_JSON, "outputs");
        Map<TeamRole, List<String>> acceptance =
                TeamPlanGenerator.parseSteps(PLAN_JSON, "acceptance");

        assertEquals(List.of("核算明细"), outputs.get(TeamRole.CALCULATOR));
        assertEquals(List.of("复核结论"), outputs.get(TeamRole.VALIDATOR));
        assertEquals(List.of("金额与政策一致", "含逐项依据"), acceptance.get(TeamRole.CALCULATOR));
    }

    @Test
    @DisplayName("缺失 steps、未知角色或非列表值一律忽略，不抛异常")
    void toleratesMalformedSteps() {
        assertTrue(TeamPlanGenerator.parseSteps("{\"roles\":{}}", "outputs").isEmpty());
        assertTrue(TeamPlanGenerator.parseSteps("not-json", "outputs").isEmpty());
        assertTrue(TeamPlanGenerator.parseSteps(null, "outputs").isEmpty());
        assertTrue(
                TeamPlanGenerator.parseSteps(
                                "{\"steps\":{\"UNKNOWN\":{\"outputs\":[\"x\"]}}}", "outputs")
                        .isEmpty());
        assertTrue(
                TeamPlanGenerator.parseSteps(
                                "{\"steps\":{\"CALCULATOR\":{\"outputs\":\"text\"}}}", "outputs")
                        .isEmpty());
    }

    @Test
    @DisplayName("步骤条目最多 8 条、每条截断到 200 字")
    void capsItems() {
        StringBuilder longText = new StringBuilder();
        longText.append("x".repeat(260));
        String json =
                "{\"steps\":{\"CALCULATOR\":{\"outputs\":["
                        + "\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"],"
                        + "\"acceptance\":[\""
                        + longText
                        + "\"]}}}";

        assertEquals(
                8, TeamPlanGenerator.parseSteps(json, "outputs").get(TeamRole.CALCULATOR).size());
        assertEquals(200, TeamPlanGenerator.parseSteps(json, "acceptance").get(TeamRole.CALCULATOR).get(0).length());
    }

    @Test
    @DisplayName("实例化计划时把产物与验收写进智能体节点，START/END 保持为空")
    void instantiateCarriesOutputs() {
        TeamPlan plan =
                TeamPlan.instantiate(
                        "plan-outputs",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "calc-agent",
                                TeamRole.VALIDATOR, "val-agent"),
                        Map.of(TeamRole.CALCULATOR, List.of("核算明细")),
                        Map.of(TeamRole.CALCULATOR, List.of("金额一致")),
                        "按能力选择",
                        List.of());

        TeamPlan.PlanNode calculator = plan.agentNode(TeamRole.CALCULATOR);
        assertEquals(List.of("核算明细"), calculator.outputs());
        assertEquals(List.of("金额一致"), calculator.acceptance());
        assertTrue(plan.agentNode(TeamRole.VALIDATOR).outputs().isEmpty());
        assertTrue(plan.node("start").outputs().isEmpty());
        assertTrue(plan.node("end").acceptance().isEmpty());
    }
}
