package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.service.AuditService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P1-1 闭环：任务编辑产出的补丁产物直接进入 G2 批次，hash 校验与审批结论一致。 */
class DevPatchApprovalFlowTest {

    @TempDir Path temp;
    private PayrollDatabase database;
    private DevTaskStore tasks;
    private DevArtifactStore artifacts;
    private DevApprovalService approvals;
    private Path source;

    @BeforeEach
    void setUp() throws IOException {
        source = temp.resolve("source");
        Files.createDirectories(source.resolve("payroll-common/src/main/java"));
        Files.createDirectories(source.resolve("frontend/src"));
        Files.writeString(source.resolve("payroll-common/src/main/java/A.java"), "class A {}\n");
        Files.writeString(source.resolve("frontend/src/App.vue"), "<template><div/></template>\n");
        initRepo(source);

        database = PayrollDatabase.inMemory("dev-patch-g2-" + System.nanoTime());
        tasks = new DevTaskStore(database);
        artifacts = new DevArtifactStore(database);
        AuditService audit = new AuditService(PayrollMappers.forDatabase(database).auditStore(), 30);
        approvals = new DevApprovalService(tasks, artifacts, new DevApprovalStore(database), audit);
        tasks.replacePlan(
                "plan-g2",
                List.of(
                        task("task-a", "后端实现", "project-backend"),
                        task("task-b", "前端实现", "project-frontend")));
    }

    @Test
    @DisplayName("会话快照的后端与前端补丁被 G2 汇总，diffHash 校验通过后整批放行")
    void snapshotsFeedG2Batch() {
        DevArtifact backendPatch;
        DevArtifact frontendPatch;
        try (DevWorkspace backendWorkspace = DevWorkspace.clone(source, temp.resolve("work/task-a"));
                DevWorkspace frontendWorkspace =
                        DevWorkspace.clone(source, temp.resolve("work/task-b"))) {
            DevEditSession backend =
                    new DevEditSession(
                            "task-a",
                            DevArtifactType.PATCH_BACKEND,
                            "dev-backend",
                            DevPathPolicy.ROLE_IMPLEMENTER,
                            backendWorkspace,
                            artifacts,
                            null);
            backend.apply(DevEdit.write("payroll-common/src/main/java/B.java", "class B {}\n"));
            backendPatch = backend.snapshot("后端实现");

            DevEditSession frontend =
                    new DevEditSession(
                            "task-b",
                            DevArtifactType.PATCH_FRONTEND,
                            "dev-frontend",
                            DevPathPolicy.ROLE_FRONTEND,
                            frontendWorkspace,
                            artifacts,
                            null);
            frontend.apply(
                    DevEdit.replace("frontend/src/App.vue", "<div/>", "<div class=\"app\"/>"));
            frontendPatch = frontend.snapshot("前端实现");
        }

        DevApprovalBatch batch = approvals.open("plan-g2");
        assertEquals(2, batch.items().size());
        assertEquals(1, approvals.pending("plan-g2").size());
        assertTrue(
                batch.items().stream()
                        .anyMatch(
                                item ->
                                        item.taskId().equals("task-a")
                                                && item.diffHash().equals(backendPatch.diffHash())));
        assertTrue(
                batch.items().stream()
                        .anyMatch(
                                item ->
                                        item.taskId().equals("task-b")
                                                && item.diffHash().equals(frontendPatch.diffHash())));

        DevApprovalBatch decided = approvals.approveAll(batch.batchId(), "alice");
        assertEquals(DevApprovalStatus.APPROVED, decided.status());
        assertEquals("alice", decided.decidedBy());
    }

    private static DevTask task(String id, String title, String owner) {
        return DevTask.pending(
                "plan-g2", id, title, "CALCULATOR", owner, 1, List.of(), List.of(), List.of());
    }

    private static void initRepo(Path dir) {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("dev-team", "dev-team@example.test")
                    .setCommitter("dev-team", "dev-team@example.test")
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }
}
