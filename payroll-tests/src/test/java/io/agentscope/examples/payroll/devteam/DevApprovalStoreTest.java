package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批批次存储：条目 JSON 往返、按计划查询与只决策一次的条件更新。 */
class DevApprovalStoreTest {

    private DevApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new DevApprovalStore(PayrollDatabase.inMemory("dev-approval-" + System.nanoTime()));
    }

    @Test
    @DisplayName("创建后可按批次与计划读回，条目字段完整往返")
    void createAndQuery() {
        DevApprovalBatch batch =
                store.create("plan-1", "G2 批量确认", List.of(item("n1", "patch-backend", 2)));

        DevApprovalBatch loaded = store.find(batch.batchId()).orElseThrow();
        assertEquals("plan-1", loaded.planId());
        assertEquals(DevApprovalStatus.PENDING, loaded.status());
        assertEquals(1, loaded.items().size());
        assertEquals("n1", loaded.items().get(0).taskId());
        assertEquals(2, loaded.items().get(0).version());
        assertEquals("hash-n1", loaded.items().get(0).diffHash());
        assertEquals(1, store.list("plan-1").size());
        assertEquals(1, store.listPending("plan-1").size());
        assertTrue(store.find("missing").isEmpty());
        assertTrue(store.find(" ").isEmpty());
    }

    @Test
    @DisplayName("只允许决策一次：二次决策返回 false 且不覆盖已有结论")
    void decideOnlyOnce() {
        DevApprovalBatch batch =
                store.create("plan-1", "G2", List.of(item("n1", "patch-backend", 1)));

        assertTrue(
                store.decide(
                        batch.batchId(),
                        DevApprovalStatus.APPROVED,
                        List.of(item("n1", "patch-backend", 1).decide(DevApprovalStatus.APPROVED, "")),
                        "alice",
                        "通过"));
        assertFalse(
                store.decide(
                        batch.batchId(),
                        DevApprovalStatus.REJECTED,
                        List.of(item("n1", "patch-backend", 1).decide(DevApprovalStatus.REJECTED, "")),
                        "bob",
                        "打回"));

        DevApprovalBatch loaded = store.find(batch.batchId()).orElseThrow();
        assertEquals(DevApprovalStatus.APPROVED, loaded.status());
        assertEquals("alice", loaded.decidedBy());
        assertEquals(DevApprovalStatus.APPROVED, loaded.items().get(0).status());
        assertTrue(store.listPending("plan-1").isEmpty());
    }

    @Test
    @DisplayName("空条目、非法计划 id 与未决策状态直接拒绝")
    void rejectsInvalidInput() {
        assertThrows(PayrollException.class, () -> store.create("plan-1", "G2", List.of()));
        assertThrows(PayrollException.class, () -> store.create("../plan", "G2", List.of(item("n1", "patch-backend", 1))));
        assertThrows(PayrollException.class, () -> store.list("../plan"));
        assertThrows(
                PayrollException.class,
                () ->
                        store.decide(
                                "batch",
                                DevApprovalStatus.PENDING,
                                List.of(item("n1", "patch-backend", 1)),
                                "alice",
                                ""));
    }

    private static DevApprovalItem item(String taskId, String type, int version) {
        return new DevApprovalItem(
                taskId,
                type,
                1L,
                version,
                "hash-" + taskId,
                DevApprovalStatus.PENDING,
                "");
    }
}
