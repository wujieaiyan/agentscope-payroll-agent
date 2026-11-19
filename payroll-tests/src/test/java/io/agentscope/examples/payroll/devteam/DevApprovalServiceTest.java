package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** G2 批量审批服务：补丁汇总、逐任务放行/打回、hash 漂移与重复决策。 */
class DevApprovalServiceTest {

    private DevTaskStore tasks;
    private DevArtifactStore artifacts;
    private DevApprovalService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("dev-g2-" + System.nanoTime());
        tasks = new DevTaskStore(database);
        artifacts = new DevArtifactStore(database);
        service =
                new DevApprovalService(
                        tasks,
                        artifacts,
                        new DevApprovalStore(database),
                        new AuditService(new H2AuditStore(database), 30));
        tasks.replacePlan(
                "plan-g2",
                List.of(
                        task("n1", "后端实现", "project-backend"),
                        task("n2", "前端实现", "project-frontend")));
    }

    @Test
    @DisplayName("打开批次汇总每个任务的最新四类补丁，旧版本不进入批次")
    void openCollectsLatestPatches() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v2", "hash-2");
        artifacts.save("n2", DevArtifactType.PATCH_FRONTEND, "dev", "", "v1", "hash-3");
        artifacts.save("n1", DevArtifactType.TEST_REPORT, "dev", "", "报告", null);

        DevApprovalBatch batch = service.open("plan-g2");

        assertEquals(2, batch.items().size());
        assertEquals(DevApprovalStatus.PENDING, batch.status());
        assertTrue(batch.summary().contains("2 个任务"));
        DevApprovalItem backend =
                batch.items().stream()
                        .filter(item -> item.artifactType().equals("patch-backend"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, backend.version());
        assertEquals("hash-2", backend.diffHash());
        assertEquals(1, service.pending("plan-g2").size());
    }

    @Test
    @DisplayName("全部通过：逐条核对 hash 后批次 APPROVED")
    void approveAllPassesWhenHashesMatch() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save("n2", DevArtifactType.PATCH_DB, "dev", "", "migration", "hash-2");
        DevApprovalBatch batch = service.open("plan-g2");

        DevApprovalBatch decided = service.approveAll(batch.batchId(), "alice");

        assertEquals(DevApprovalStatus.APPROVED, decided.status());
        assertEquals("alice", decided.decidedBy());
        assertTrue(
                decided.items().stream()
                        .allMatch(item -> item.status() == DevApprovalStatus.APPROVED));
    }

    @Test
    @DisplayName("确认期间补丁被改写：条目置 STALE，不下发旧内容")
    void detectsStaleDiffHash() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save("n2", DevArtifactType.PATCH_FRONTEND, "dev", "", "v1", "hash-2");
        DevApprovalBatch batch = service.open("plan-g2");
        artifacts.save("n2", DevArtifactType.PATCH_FRONTEND, "dev", "", "v2", "hash-3");

        DevApprovalBatch decided = service.approveAll(batch.batchId(), "alice");

        assertEquals(DevApprovalStatus.STALE, decided.status());
        DevApprovalItem backend = item(decided, "n1");
        DevApprovalItem frontend = item(decided, "n2");
        assertEquals(DevApprovalStatus.APPROVED, backend.status());
        assertEquals(DevApprovalStatus.STALE, frontend.status());
    }

    @Test
    @DisplayName("逐任务决策：未勾选任务打回，其余放行；重复决策不再改变结论")
    void decidesPerTaskAndIsIdempotent() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save("n2", DevArtifactType.PATCH_FRONTEND, "dev", "", "v1", "hash-2");
        DevApprovalBatch batch = service.open("plan-g2");

        DevApprovalBatch decided =
                service.decide(batch.batchId(), List.of("n2"), "alice", "只收前端");
        assertEquals(DevApprovalStatus.APPROVED, decided.status());
        assertEquals(DevApprovalStatus.REJECTED, item(decided, "n1").status());
        assertEquals(DevApprovalStatus.APPROVED, item(decided, "n2").status());

        DevApprovalBatch repeated =
                service.decide(batch.batchId(), List.of("n1"), "bob", "重试");
        assertEquals(decided.status(), repeated.status());
        assertEquals("alice", repeated.decidedBy());
        assertEquals(DevApprovalStatus.REJECTED, item(repeated, "n1").status());
    }

    @Test
    @DisplayName("全部打回时批次 REJECTED；空计划、未知任务与缺失批次直接拒绝")
    void rejectsAndValidatesInput() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        DevApprovalBatch batch = service.open("plan-g2");

        assertThrows(PayrollException.class, () -> service.open("plan-empty"));
        assertThrows(
                PayrollException.class,
                () -> service.decide(batch.batchId(), List.of("missing"), "alice", ""));
        assertThrows(PayrollNotFoundException.class, () -> service.approveAll("missing", "alice"));

        DevApprovalBatch rejected = service.decide(batch.batchId(), List.of(), "alice", "");
        assertEquals(DevApprovalStatus.REJECTED, rejected.status());
    }

    @Test
    @DisplayName("审查报告存在阻断项时拒绝进入 G2，批次不落库")
    void blocksBatchWhenReviewHasBlockingFinding() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save(
                "n1",
                DevArtifactType.REVIEW_REPORT,
                "reviewer",
                "",
                report("- [阻断] payroll-common/src/A.java:12 — 空指针未处理"),
                null);

        assertThrows(PayrollException.class, () -> service.open("plan-g2"));
        assertTrue(service.pending("plan-g2").isEmpty());
    }

    @Test
    @DisplayName("审查报告不完整时同样拒绝进入 G2")
    void blocksBatchWhenReviewIsIncomplete() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save("n1", DevArtifactType.REVIEW_REPORT, "reviewer", "", "审查通过", null);

        assertThrows(PayrollException.class, () -> service.open("plan-g2"));
        assertTrue(service.pending("plan-g2").isEmpty());
    }

    @Test
    @DisplayName("无阻断的审查结论并入批次摘要")
    void mergesCleanReviewSummary() {
        artifacts.save("n1", DevArtifactType.PATCH_BACKEND, "dev", "", "v1", "hash-1");
        artifacts.save(
                "n1",
                DevArtifactType.REVIEW_REPORT,
                "reviewer",
                "",
                report("- [建议] payroll-common/src/A.java:12 — 建议补日志"),
                null);

        DevApprovalBatch batch = service.open("plan-g2");

        assertEquals(1, batch.items().size());
        assertTrue(batch.summary().contains("审查：1 份报告，0 阻断，建议 1 项"), batch.summary());
    }

    @Test
    @DisplayName("契约/迁移/依赖补丁不能批量放行，必须逐任务确认")
    void escalatesSensitivePatches() {
        artifacts.save(
                "n1",
                DevArtifactType.PATCH_DB,
                "dev",
                "",
                """
                +++ b/payroll-common/src/main/resources/db/migration/mysql/V15__dev.sql
                +ALTER TABLE payroll_item ADD COLUMN note VARCHAR(64);
                """,
                "hash-1");
        DevApprovalBatch batch = service.open("plan-g2");

        assertTrue(batch.summary().contains("需逐任务确认：n1"), batch.summary());
        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> service.approveAll(batch.batchId(), "alice"));
        assertTrue(error.getMessage().contains("n1"), error.getMessage());
        assertEquals(1, service.pending("plan-g2").size(), "拒绝批量放行后批次仍待确认");

        DevApprovalBatch decided =
                service.decide(batch.batchId(), List.of("n1"), "alice", "逐任务确认");
        assertEquals(DevApprovalStatus.APPROVED, item(decided, "n1").status());
    }

    @Test
    @DisplayName("敏感路径识别：契约、迁移 SQL 与依赖清单")
    void detectsSensitiveDiffPaths() {
        assertTrue(DevApprovalService.requiresPerTask("+++ b/docs/interfaces.md\n+x"));
        assertTrue(
                DevApprovalService.requiresPerTask(
                        "+++ b/payroll-common/src/main/resources/db/migration/mysql/V15__x.sql"));
        assertTrue(DevApprovalService.requiresPerTask("+++ b/payroll-common/pom.xml"));
        assertTrue(DevApprovalService.requiresPerTask("+++ b/frontend/package-lock.json"));
        assertTrue(DevApprovalService.requiresPerTask("+++ b/backend/requirements.txt"));
        assertFalse(DevApprovalService.requiresPerTask("+++ b/backend/personalize.js"));
        assertFalse(DevApprovalService.requiresPerTask("+++ b/db/report.sql"));
        assertFalse(DevApprovalService.requiresPerTask(null));
    }

    private static String report(String... findings) {
        return """
                ### 检查清单
                - [ ] 契约一致性
                - [ ] 正确性
                - [ ] 安全边界
                - [ ] 测试覆盖
                ### 逐文件结论
                """
                + String.join("\n", findings)
                + "\n";
    }

    private static DevApprovalItem item(DevApprovalBatch batch, String taskId) {
        return batch.items().stream()
                .filter(item -> item.taskId().equals(taskId))
                .findFirst()
                .orElseThrow();
    }

    private static DevTask task(String id, String title, String owner) {
        return DevTask.pending(
                "plan-g2",
                id,
                title,
                "CALCULATOR",
                owner,
                1,
                List.of(),
                List.of(),
                List.of());
    }
}
