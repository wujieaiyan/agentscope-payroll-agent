package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.team.TeamIntent;
import io.agentscope.examples.payroll.team.TeamPlan;
import io.agentscope.examples.payroll.team.TeamRole;
import io.agentscope.examples.payroll.team.TeamTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P0-4：计划 → tasks.json → 产物库，依赖、产物与验收都能往返读取。 */
class DevTaskListTest {

    private DevArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new DevArtifactStore(PayrollDatabase.inMemory("dev-task-list-" + System.nanoTime()));
    }

    @Test
    @DisplayName("tasks.json 按智能体节点输出依赖、产物与验收，START/END 不出现在任务里")
    void serializesPlanTasks() {
        TeamPlan plan = plan();

        Map<String, Object> root =
                JsonSupport.fromJson(DevTaskList.toJson(plan), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) root.get("tasks");

        assertEquals("plan-dev-1", root.get("plan_id"));
        assertEquals("CALC_VALIDATE", root.get("template_id"));
        assertEquals("按能力选择", root.get("rationale"));
        assertEquals(2, tasks.size());

        Map<String, Object> calculator = tasks.get(0);
        assertEquals("n1", calculator.get("id"));
        assertEquals("CALCULATOR", calculator.get("role"));
        assertEquals("calc-agent", calculator.get("agent_id"));
        assertEquals(List.of(), calculator.get("depends_on"));
        assertEquals(List.of("核算明细"), calculator.get("outputs"));
        assertEquals(List.of("金额与政策一致"), calculator.get("acceptance"));

        Map<String, Object> validator = tasks.get(1);
        assertEquals("n2", validator.get("id"));
        assertEquals(List.of("n1"), validator.get("depends_on"));
        assertEquals(List.of("复核结论"), validator.get("outputs"));
        assertTrue(tasks.stream().noneMatch(task -> "start".equals(task.get("id"))));
        assertTrue(tasks.stream().noneMatch(task -> "end".equals(task.get("id"))));
    }

    @Test
    @DisplayName("任务清单落库为 project-manager 的 tasks 产物，重复保存不新增版本")
    void persistsTasksArtifact() {
        DevPlanArtifacts artifacts = new DevPlanArtifacts(store);
        TeamPlan plan = plan();

        DevArtifact first = artifacts.saveTasks(null, plan);
        DevArtifact again = artifacts.saveTasks("", plan);

        assertEquals(plan.planId(), first.taskId());
        assertEquals(DevArtifactType.TASKS.id(), first.artifactType());
        assertEquals(DevPlanArtifacts.OWNER, first.owner());
        assertEquals(DevTaskList.toJson(plan), first.content());
        assertEquals(1, first.version());
        assertEquals(first.artifactId(), again.artifactId());
        assertTrue(first.relativePath().endsWith("/tasks.json"));
        assertEquals(1, store.history(plan.planId(), DevArtifactType.TASKS).size());
    }

    private static TeamPlan plan() {
        return TeamPlan.instantiate(
                "plan-dev-1",
                TeamIntent.TASK_CALCULATE,
                TeamTemplate.CALC_VALIDATE,
                Map.of(
                        TeamRole.CALCULATOR, "calc-agent",
                        TeamRole.VALIDATOR, "val-agent"),
                Map.of(
                        TeamRole.CALCULATOR, List.of("核算明细"),
                        TeamRole.VALIDATOR, List.of("复核结论")),
                Map.of(
                        TeamRole.CALCULATOR, List.of("金额与政策一致"),
                        TeamRole.VALIDATOR, List.of("差异为 0")),
                "按能力选择",
                List.of("冷启动选角"));
    }
}
