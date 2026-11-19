package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.team.TeamPlan;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevTaskBoardTest {

    private DevTaskBoard board;
    private TeamPlan plan;

    @BeforeEach
    void setUp() {
        board = new DevTaskBoard(new DevTaskStore(PayrollDatabase.inMemory("dev-board-" + System.nanoTime())));
        plan = DevTaskPlannerTest.plan();
    }

    @Test
    @DisplayName("发布计划后无依赖任务待执行，有依赖任务保持待开始")
    void publishPromotesRootTasks() {
        Map<String, DevTask> tasks = byTaskId(board.publish(plan));

        assertEquals(DevTaskStatus.READY, tasks.get("n1").status());
        assertEquals(DevTaskStatus.PENDING, tasks.get("n2").status());
        assertEquals("calc-agent", tasks.get("n1").ownerAgent());
        assertEquals(List.of("n1"), board.blockedBy(plan.planId(), "n2"));
    }

    @Test
    @DisplayName("依赖完成后自动提升下游任务，禁止跳过依赖直接执行")
    void dependencyChainUnlocksNextTask() {
        board.publish(plan);
        assertThrows(
                PayrollException.class,
                () -> board.update(plan.planId(), "n2", DevTaskStatus.RUNNING, ""));

        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "开始");
        Map<String, DevTask> done =
                byTaskId(board.update(plan.planId(), "n1", DevTaskStatus.DONE, "构建通过"));

        assertEquals(DevTaskStatus.DONE, done.get("n1").status());
        assertEquals(DevTaskStatus.READY, done.get("n2").status());
        assertTrue(done.get("n2").note().contains("依赖已就绪"), done.get("n2").note());
    }

    @Test
    @DisplayName("依赖失败时下游阻塞，重试恢复后重新排队并最终解锁")
    void dependencyFailureBlocksAndRecovers() {
        board.publish(plan);
        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "开始");
        Map<String, DevTask> failed =
                byTaskId(board.update(plan.planId(), "n1", DevTaskStatus.FAILED, "构建失败"));

        assertEquals(DevTaskStatus.BLOCKED, failed.get("n2").status());
        assertTrue(failed.get("n2").note().contains("依赖任务失败"), failed.get("n2").note());

        Map<String, DevTask> retried =
                byTaskId(board.update(plan.planId(), "n1", DevTaskStatus.READY, "重试"));
        assertEquals(DevTaskStatus.PENDING, retried.get("n2").status());
        assertEquals("等待依赖重试", retried.get("n2").note());

        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "重试");
        Map<String, DevTask> recovered =
                byTaskId(board.update(plan.planId(), "n1", DevTaskStatus.DONE, "通过"));
        assertEquals(DevTaskStatus.READY, recovered.get("n2").status());
    }

    @Test
    @DisplayName("非法状态流转与终态改派直接拒绝")
    void rejectsIllegalTransitions() {
        board.publish(plan);
        assertThrows(
                PayrollException.class,
                () -> board.update(plan.planId(), "n1", DevTaskStatus.DONE, ""));

        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "");
        board.update(plan.planId(), "n1", DevTaskStatus.DONE, "");
        assertThrows(
                PayrollException.class,
                () -> board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, ""));
        assertThrows(
                PayrollException.class,
                () -> board.assign(plan.planId(), "n1", "project-reviewer"));
    }

    @Test
    @DisplayName("改派负责人写回面板，任务不存在时报错")
    void assignsOwner() {
        board.publish(plan);
        Map<String, DevTask> tasks =
                byTaskId(board.assign(plan.planId(), "n2", "project-reviewer"));

        assertEquals("project-reviewer", tasks.get("n2").ownerAgent());
        assertThrows(
                PayrollException.class,
                () -> board.assign(plan.planId(), "missing", "project-reviewer"));
    }

    @Test
    @DisplayName("人工重试：失败任务次数加一，依赖未恢复时回到等待或阻塞")
    void retryRequeuesWithDependencyCheck() {
        board.publish(plan);
        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "开始");
        board.update(plan.planId(), "n1", DevTaskStatus.FAILED, "构建失败");

        Map<String, DevTask> downstream =
                byTaskId(board.retry(plan.planId(), "n2"));
        assertEquals(DevTaskStatus.BLOCKED, downstream.get("n2").status());
        assertEquals(1, downstream.get("n2").attempt());

        Map<String, DevTask> retried = byTaskId(board.retry(plan.planId(), "n1"));
        assertEquals(DevTaskStatus.READY, retried.get("n1").status());
        assertEquals(1, retried.get("n1").attempt());
        assertEquals(DevTaskStatus.PENDING, retried.get("n2").status());
        assertEquals("等待依赖重试", retried.get("n2").note());

        board.update(plan.planId(), "n1", DevTaskStatus.RUNNING, "重试");
        Map<String, DevTask> recovered =
                byTaskId(board.update(plan.planId(), "n1", DevTaskStatus.DONE, "通过"));
        assertEquals(DevTaskStatus.READY, recovered.get("n2").status());
        assertThrows(
                PayrollException.class, () -> board.retry(plan.planId(), "n1"));
    }

    private static Map<String, DevTask> byTaskId(List<DevTask> tasks) {
        return tasks.stream().collect(Collectors.toMap(DevTask::taskId, Function.identity()));
    }
}
