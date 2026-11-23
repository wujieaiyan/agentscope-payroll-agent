package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.team.TeamIntent;
import io.agentscope.examples.payroll.team.TeamPlan;
import io.agentscope.examples.payroll.team.TeamRole;
import io.agentscope.examples.payroll.team.TeamTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevTaskPlannerTest {

    @Test
    @DisplayName("计划节点展开为任务：负责人、依赖、产物、验收与优先级同源")
    void mapsPlanNodesToTasks() {
        List<DevTask> tasks = DevTaskPlanner.plan(plan());

        assertEquals(2, tasks.size());
        DevTask first = tasks.get(0);
        assertEquals("n1", first.taskId());
        assertEquals("CALCULATOR", first.role());
        assertEquals("calc-agent", first.ownerAgent());
        assertEquals(DevTaskStatus.PENDING, first.status());
        assertEquals(2, first.priority());
        assertEquals(List.of(), first.dependsOn());
        assertEquals(List.of("patch-backend.diff"), first.outputs());
        assertEquals(List.of("mvn test 通过"), first.acceptance());

        DevTask second = tasks.get(1);
        assertEquals("n2", second.taskId());
        assertEquals(1, second.priority());
        assertEquals(List.of("n1"), second.dependsOn());
        assertEquals(List.of("review-report.md"), second.outputs());
        assertTrue(second.title().contains("验证"), second.title());
    }

    @Test
    @DisplayName("空计划直接拒绝")
    void rejectsNullPlan() {
        assertThrows(IllegalArgumentException.class, () -> DevTaskPlanner.plan(null));
    }

    @Test
    @DisplayName("产出补丁的角色自动追加依赖它的审查任务")
    void appendsReviewTaskForPatchRole() {
        TeamPlan plan =
                nodePlan(
                        "plan-review-" + System.nanoTime(),
                        List.of(node("n1", "project-implementer", "impl-agent")),
                        List.of());

        List<DevTask> tasks = DevTaskPlanner.plan(plan);

        assertEquals(2, tasks.size());
        DevTask review = tasks.get(1);
        assertEquals(DevTaskPlanner.REVIEW_PREFIX + "n1", review.taskId());
        assertEquals(DevPathPolicy.ROLE_REVIEWER, review.role());
        assertEquals("", review.ownerAgent());
        assertEquals(1, review.priority());
        assertEquals(List.of("n1"), review.dependsOn());
        assertEquals(List.of("review-report.md"), review.outputs());
        assertEquals(DevTaskStatus.PENDING, review.status());
        assertTrue(review.title().contains("代码审查"), review.title());
    }

    @Test
    @DisplayName("计划已显式编排审查节点时不重复追加")
    void keepsExplicitReviewNode() {
        TeamPlan plan =
                nodePlan(
                        "plan-review-explicit-" + System.nanoTime(),
                        List.of(
                                node("n1", "project-implementer", "impl-agent"),
                                node("n2", "project-reviewer", "reviewer-agent")),
                        List.of(new TeamPlan.PlanEdge("e1", "n1", "n2", null)));

        List<DevTask> tasks = DevTaskPlanner.plan(plan);

        assertEquals(2, tasks.size());
        assertTrue(
                tasks.stream().noneMatch(task -> task.taskId().startsWith(DevTaskPlanner.REVIEW_PREFIX)));
        assertEquals(List.of("n1"), tasks.get(1).dependsOn());
    }

    private static TeamPlan nodePlan(
            String planId, List<TeamPlan.PlanNode> nodes, List<TeamPlan.PlanEdge> edges) {
        return new TeamPlan(
                planId,
                TeamIntent.TASK_CALCULATE,
                "dev-custom",
                nodes,
                edges,
                Map.of(),
                "",
                List.of());
    }

    private static TeamPlan.PlanNode node(String id, String role, String agentId) {
        return new TeamPlan.PlanNode(
                id,
                FlowNode.TYPE_AGENT,
                role,
                agentId,
                id,
                "",
                "",
                List.of(),
                List.of());
    }

    static TeamPlan plan() {
        return TeamPlan.instantiate(
                "plan-tasks-" + System.nanoTime(),
                TeamIntent.TASK_CALCULATE,
                TeamTemplate.CALC_VALIDATE,
                Map.of(TeamRole.CALCULATOR, "calc-agent", TeamRole.VALIDATOR, "val-agent"),
                Map.of(
                        TeamRole.CALCULATOR, List.of("patch-backend.diff"),
                        TeamRole.VALIDATOR, List.of("review-report.md")),
                Map.of(
                        TeamRole.CALCULATOR, List.of("mvn test 通过"),
                        TeamRole.VALIDATOR, List.of("无阻断项")),
                "",
                List.of());
    }
}
