package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevTaskStoreTest {

    private DevTaskStore store;

    @BeforeEach
    void setUp() {
        store = new DevTaskStore(PayrollDatabase.inMemory("dev-task-" + System.nanoTime()));
    }

    @Test
    @DisplayName("整体替换计划：重复发布不累积，依赖与产物按 JSON 往返")
    void replacePlanIsIdempotent() {
        store.replacePlan("plan-1", List.of(task("n1", "设计", 2), task("n2", "实现", 1)));
        List<DevTask> tasks = store.replacePlan("plan-1", List.of(task("n1", "设计", 2), task("n2", "实现", 1)));

        assertEquals(2, tasks.size());
        assertEquals(List.of("n1"), tasks.get(1).dependsOn());
        assertEquals(List.of("patch-backend.diff"), tasks.get(0).outputs());
        assertEquals(List.of("mvn test"), tasks.get(0).acceptance());
        assertTrue(tasks.get(0).id() > 0);
        assertNotEquals(tasks.get(0).id(), tasks.get(1).id());
    }

    @Test
    @DisplayName("按计划隔离，更新状态与改派负责人后可按任务读回")
    void updatesStatusAndOwner() {
        store.replacePlan("plan-1", List.of(task("n1", "设计", 2), task("n2", "实现", 1)));
        store.replacePlan("plan-2", List.of(task("n1", "另一计划", 2)));

        assertTrue(store.updateStatus("plan-1", "n1", DevTaskStatus.RUNNING, "开始"));
        assertTrue(store.assign("plan-1", "n1", "project-implementer"));
        DevTask updated = store.find("plan-1", "n1").orElseThrow();

        assertEquals(DevTaskStatus.RUNNING, updated.status());
        assertEquals("开始", updated.note());
        assertEquals("project-implementer", updated.ownerAgent());
        assertEquals(DevTaskStatus.PENDING, store.find("plan-2", "n1").orElseThrow().status());
        assertEquals(1, store.list("plan-2").size());
        assertFalse(store.updateStatus("plan-1", "missing", DevTaskStatus.DONE, ""));
    }

    @Test
    @DisplayName("非法计划/任务 id、空标题与空角色直接拒绝")
    void rejectsInvalidRows() {
        assertThrows(PayrollException.class, () -> store.list("../plan"));
        assertThrows(
                PayrollException.class,
                () -> store.replacePlan("plan-1", List.of(task("", "设计", 1))));
        assertThrows(
                PayrollException.class,
                () ->
                        store.replacePlan(
                                "plan-1",
                                List.of(
                                        DevTask.pending(
                                                "plan-1", "n1", " ", "CALCULATOR", "", 1,
                                                List.of(), List.of(), List.of()))));
        assertThrows(
                PayrollException.class,
                () ->
                        store.replacePlan(
                                "plan-1",
                                List.of(
                                        DevTask.pending(
                                                "plan-1", "n1", "设计", "", "", 1,
                                                List.of(), List.of(), List.of()))));
    }

    @Test
    @DisplayName("执行字段：开始/结束写时间与失败原因，重试次数递增并清空痕迹")
    void tracksExecutionLifecycle() {
        store.replacePlan("plan-1", List.of(task("n1", "设计", 2)));

        assertTrue(store.start("plan-1", "n1", "执行中"));
        DevTask running = store.find("plan-1", "n1").orElseThrow();
        assertEquals(DevTaskStatus.RUNNING, running.status());
        assertEquals("", running.error());
        assertTrue(running.startedAt() != null);
        assertTrue(running.finishedAt() == null);

        assertTrue(store.finish("plan-1", "n1", DevTaskStatus.FAILED, "执行失败：模拟", "boom"));
        DevTask failed = store.find("plan-1", "n1").orElseThrow();
        assertEquals(DevTaskStatus.FAILED, failed.status());
        assertEquals("boom", failed.error());
        assertTrue(failed.startedAt() != null);
        assertTrue(failed.finishedAt() != null);

        assertTrue(store.retry("plan-1", "n1", "人工重试"));
        DevTask retried = store.find("plan-1", "n1").orElseThrow();
        assertEquals(DevTaskStatus.READY, retried.status());
        assertEquals(1, retried.attempt());
        assertEquals("", retried.error());
        assertTrue(retried.startedAt() == null);
        assertTrue(retried.finishedAt() == null);
    }

    @Test
    @DisplayName("启动恢复只把 RUNNING 行置为失败，其它状态保持不变")
    void recoversInterruptedRuns() {
        store.replacePlan("plan-1", List.of(task("n1", "设计", 2), task("n2", "实现", 1)));
        store.start("plan-1", "n1", "执行中");

        assertEquals(1, store.recoverInterrupted("实例重启", "task-interrupted"));

        DevTask recovered = store.find("plan-1", "n1").orElseThrow();
        assertEquals(DevTaskStatus.FAILED, recovered.status());
        assertEquals("task-interrupted", recovered.error());
        assertEquals("实例重启", recovered.note());
        assertTrue(recovered.finishedAt() != null);
        assertEquals(DevTaskStatus.PENDING, store.find("plan-1", "n2").orElseThrow().status());
        assertEquals(0, store.recoverInterrupted("实例重启", "task-interrupted"));
    }

    private static DevTask task(String id, String title, int priority) {
        return DevTask.pending(
                "plan-1",
                id,
                title,
                "CALCULATOR",
                "calc-agent",
                priority,
                "n2".equals(id) ? List.of("n1") : List.of(),
                List.of("patch-backend.diff"),
                List.of("mvn test"));
    }
}
