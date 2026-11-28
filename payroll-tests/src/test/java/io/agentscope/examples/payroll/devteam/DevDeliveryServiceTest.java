package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevDeliveryStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G3 交付：冻结补丁、PR 描述、本地建分支/commit、漂移与驳回。 */
class DevDeliveryServiceTest {

    private static final String PLAN = "plan-g3";
    private static final String BRANCH = "dev-team/demo";

    @TempDir Path temp;

    private Path projectRoot;
    private DevArtifactStore artifacts;
    private DevApprovalService approvals;
    private DevDeliveryService service;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = initRepo(temp.resolve("project"));
        PayrollDatabase database = PayrollDatabase.inMemory("dev-g3-" + System.nanoTime());
        DevTaskStore tasks = new DevTaskStore(database);
        tasks.replacePlan(
                PLAN,
                List.of(
                        task("n1", "后端实现", "impl-agent"),
                        task("n2", "前端实现", "fe-agent")));
        artifacts = new DevArtifactStore(database);
        AuditService audit = new AuditService(PayrollMappers.forDatabase(database).auditStore(), 30);
        approvals =
                new DevApprovalService(
                        tasks, artifacts, new DevApprovalStore(database), audit);
        service =
                new DevDeliveryService(
                        tasks,
                        artifacts,
                        approvals,
                        new DevDeliveryStore(database),
                        projectRoot,
                        temp.resolve("work"),
                        audit);
    }

    @Test
    @DisplayName("打开交付申请：冻结补丁版本并生成含审查、回滚与未推送说明的 PR 描述")
    void opensDeliveryWithPrDescription() throws Exception {
        approvedPlan();

        DevDelivery delivery = service.open(PLAN, BRANCH);

        assertEquals(DevDeliveryStatus.PENDING, delivery.status());
        assertEquals(2, delivery.patches().size());
        assertTrue(delivery.prTitle().startsWith("[dev-team] " + PLAN), delivery.prTitle());
        assertTrue(delivery.prBody().contains("## 变更摘要"));
        assertTrue(delivery.prBody().contains("### 审查结论"));
        assertTrue(delivery.prBody().contains("### 测试结果"));
        assertTrue(delivery.prBody().contains("未推送"));
        assertEquals(head(projectRoot), delivery.baseCommit());
        assertEquals(1, service.list(PLAN).size());
    }

    @Test
    @DisplayName("G3 通过后本地建分支并提交，主工作区不受影响，重复确认幂等")
    void approvesAndCreatesBranch() throws Exception {
        approvedPlan();
        DevDelivery delivery = service.open(PLAN, BRANCH);
        String untouched = Files.readString(projectRoot.resolve("app.txt"));

        DevDelivery approved = service.approve(delivery.deliveryId(), "alice");

        assertEquals(DevDeliveryStatus.APPROVED, approved.status());
        assertFalse(approved.commitId().isBlank());
        assertEquals(approved.commitId(), ref(BRANCH).name());
        assertEquals(untouched, Files.readString(projectRoot.resolve("app.txt")));
        assertEquals("new feature\n", committedFile(approved.commitId(), "feature.txt"));
        assertEquals(
                approved.commitId(), service.approve(delivery.deliveryId(), "bob").commitId());
    }

    @Test
    @DisplayName("确认期间补丁被改写：交付置 STALE，不建分支")
    void marksStaleWhenPatchChanges() throws Exception {
        approvedPlan();
        DevDelivery delivery = service.open(PLAN, BRANCH);
        artifacts.save(
                "n1",
                DevArtifactType.PATCH_BACKEND,
                "dev",
                "",
                diffOf("app.txt", "line1\nline2 v3\n"),
                "hash-3");

        DevDelivery decided = service.approve(delivery.deliveryId(), "alice");

        assertEquals(DevDeliveryStatus.STALE, decided.status());
        assertTrue(decided.commitId().isEmpty());
        assertNull(ref(BRANCH));
    }

    @Test
    @DisplayName("G3 驳回后不建分支，再次确认保持驳回结论")
    void rejectsWithoutBranch() throws Exception {
        approvedPlan();
        DevDelivery delivery = service.open(PLAN, BRANCH);

        DevDelivery rejected = service.reject(delivery.deliveryId(), "alice", "下周再交付");

        assertEquals(DevDeliveryStatus.REJECTED, rejected.status());
        assertNull(ref(BRANCH));
        assertEquals(
                DevDeliveryStatus.REJECTED,
                service.approve(delivery.deliveryId(), "bob").status());
    }

    @Test
    @DisplayName("G2 未通过、缺审查报告或分支非法时拒绝打开交付")
    void validatesPreconditions() throws Exception {
        savePatches();
        artifacts.save("n1", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        artifacts.save("n2", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        assertThrows(PayrollException.class, () -> service.open(PLAN, BRANCH));

        DevApprovalBatch batch = approvals.open(PLAN);
        assertThrows(PayrollException.class, () -> service.open(PLAN, BRANCH));
        approvals.approveAll(batch.batchId(), "alice");
        assertThrows(PayrollException.class, () -> service.open(PLAN, "main"));
        assertThrows(PayrollException.class, () -> service.open(PLAN, "bad branch"));
    }

    @Test
    @DisplayName("补丁缺少审查报告或分支已存在时拒绝交付")
    void requiresReviewAndFreeBranch() throws Exception {
        savePatches();
        artifacts.save("n1", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        DevApprovalBatch batch = approvals.open(PLAN);
        approvals.approveAll(batch.batchId(), "alice");

        assertThrows(PayrollException.class, () -> service.open(PLAN, BRANCH));

        artifacts.save("n2", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        createBranch(BRANCH);
        assertThrows(PayrollException.class, () -> service.open(PLAN, BRANCH));
    }

    @Test
    @DisplayName("未知交付申请直接拒绝")
    void rejectsUnknownDelivery() {
        assertThrows(
                PayrollNotFoundException.class, () -> service.approve("missing", "alice"));
    }

    private void approvedPlan() throws Exception {
        savePatches();
        artifacts.save("n1", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        artifacts.save("n2", DevArtifactType.REVIEW_REPORT, "reviewer", "", cleanReport(), null);
        DevApprovalBatch batch = approvals.open(PLAN);
        approvals.approveAll(batch.batchId(), "alice");
    }

    private void savePatches() throws Exception {
        artifacts.save(
                "n1",
                DevArtifactType.PATCH_BACKEND,
                "dev",
                "",
                diffOf("app.txt", "line1\nline2 changed\n"),
                "hash-1");
        artifacts.save(
                "n2",
                DevArtifactType.PATCH_FRONTEND,
                "dev",
                "",
                diffOf("feature.txt", "new feature\n"),
                "hash-2");
    }

    private static String cleanReport() {
        return """
                ### 检查清单
                - [ ] 契约一致性
                - [ ] 正确性
                - [ ] 安全边界
                - [ ] 测试覆盖
                ### 逐文件结论
                - [通过] payroll-common/src/A.java:12 — 契约一致
                """;
    }

    private String diffOf(String relative, String content) throws Exception {
        Path file = projectRoot.resolve(relative);
        boolean existed = Files.exists(file);
        try (Git git = Git.open(projectRoot.toFile())) {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
            git.add().addFilepattern(".").call();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            git.diff().setCached(true).setOutputStream(out).call();
            git.reset().setMode(ResetType.HARD).call();
            if (!existed) {
                Files.deleteIfExists(file);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private ObjectId ref(String branch) throws IOException {
        try (Git git = Git.open(projectRoot.toFile())) {
            return git.getRepository().resolve("refs/heads/" + branch);
        }
    }

    private String committedFile(String commitId, String path) throws IOException {
        try (Git git = Git.open(projectRoot.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                    assertNotNull(tree, path);
                    return new String(
                            repository.open(tree.getObjectId(0)).getBytes(),
                            StandardCharsets.UTF_8);
                }
            }
        }
    }

    private void createBranch(String branch) throws IOException, GitAPIException {
        try (Git git = Git.open(projectRoot.toFile())) {
            git.branchCreate().setName(branch).call();
        }
    }

    private static String head(Path repo) throws IOException {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().resolve("HEAD").name();
        }
    }

    private static Path initRepo(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            Files.writeString(dir.resolve("app.txt"), "line1\nline2\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("dev-team", "dev-team@example.test")
                    .setCommitter("dev-team", "dev-team@example.test")
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }
        return dir;
    }

    private static DevTask task(String id, String title, String owner) {
        return DevTask.pending(
                PLAN, id, title, "CALCULATOR", owner, 1, List.of(), List.of(), List.of());
    }
}
