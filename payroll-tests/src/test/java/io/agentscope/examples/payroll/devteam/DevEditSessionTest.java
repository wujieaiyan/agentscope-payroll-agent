package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuditPage;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevEditSessionTest {

    private static final String BACKEND_FILE = "payroll-common/src/main/java/A.java";

    @TempDir Path temp;
    private DevArtifactStore artifacts;
    private AuditService audit;
    private DevWorkspace workspace;

    @BeforeEach
    void setUp() throws IOException {
        Path source = temp.resolve("source");
        Files.createDirectories(source.resolve("payroll-common/src/main/java"));
        Files.writeString(source.resolve(BACKEND_FILE), "class A {\n    int v = 1;\n}\n");
        Files.writeString(source.resolve("README.md"), "demo\n");
        initRepo(source);
        workspace = DevWorkspace.clone(source, temp.resolve("work/task-a"));
        PayrollDatabase database = PayrollDatabase.inMemory("dev-edit-" + System.nanoTime());
        artifacts = new DevArtifactStore(database);
        audit = new AuditService(new H2AuditStore(database), 30);
    }

    @AfterEach
    void tearDown() {
        if (workspace != null) {
            workspace.close();
        }
    }

    @Test
    @DisplayName("结构化替换后生成后端补丁产物：diffHash 由存储层计算，内容含 diff 头")
    void snapshotSavesPatchArtifact() throws IOException {
        DevEditSession session = session(DevArtifactType.PATCH_BACKEND, DevPathPolicy.ROLE_IMPLEMENTER);

        session.apply(DevEdit.replace(BACKEND_FILE, "int v = 1;", "int v = 2;"));
        DevArtifact artifact = session.snapshot("实现需求 A");

        assertEquals(DevArtifactType.PATCH_BACKEND.id(), artifact.artifactType());
        assertEquals(1, artifact.version());
        assertEquals("dev-backend", artifact.owner());
        assertNotNull(artifact.diffHash());
        assertEquals(artifact.contentHash(), artifact.diffHash());
        assertTrue(artifact.content().contains("diff --git a/" + BACKEND_FILE));
        assertTrue(artifact.content().contains("+    int v = 2;"));
        assertEquals(List.of(BACKEND_FILE), session.touchedFiles());
        assertEquals(artifact, session.lastSnapshot().orElseThrow());
        assertTrue(Files.readString(workspace.root().resolve(BACKEND_FILE)).contains("int v = 2;"));
    }

    @Test
    @DisplayName("相同内容重复快照不产生新版本；改内容后版本递增")
    void snapshotDeduplicates() {
        DevEditSession session = session(DevArtifactType.PATCH_BACKEND, DevPathPolicy.ROLE_IMPLEMENTER);
        session.apply(DevEdit.replace(BACKEND_FILE, "int v = 1;", "int v = 2;"));

        DevArtifact first = session.snapshot("实现需求 A");
        DevArtifact repeated = session.snapshot("实现需求 A");
        assertEquals(first.version(), repeated.version());
        assertEquals(first.artifactId(), repeated.artifactId());

        session.apply(DevEdit.replace(BACKEND_FILE, "int v = 2;", "int v = 3;"));
        DevArtifact next = session.snapshot("实现需求 A");
        assertEquals(2, next.version());
    }

    @Test
    @DisplayName("未编辑或编辑无差异时拒绝生成补丁")
    void refusesEmptySnapshot() throws IOException {
        DevEditSession session = session(DevArtifactType.PATCH_BACKEND, DevPathPolicy.ROLE_IMPLEMENTER);
        assertThrows(PayrollException.class, () -> session.snapshot("空"));

        String current = Files.readString(workspace.root().resolve(BACKEND_FILE));
        session.apply(DevEdit.write(BACKEND_FILE, current));
        assertTrue(session.touchedFiles().isEmpty());
        assertThrows(PayrollException.class, () -> session.snapshot("空"));
    }

    @Test
    @DisplayName("越权路径与只读角色直接拒绝，且不记入 touched")
    void deniesOutOfScopeEdits() {
        DevEditSession product = session(DevArtifactType.PATCH_FRONTEND, DevPathPolicy.ROLE_PRODUCT);
        assertThrows(
                PayrollException.class,
                () -> product.apply(DevEdit.write("frontend/src/App.vue", "<template/>")));
        assertTrue(product.touchedFiles().isEmpty());

        DevEditSession frontend = session(DevArtifactType.PATCH_FRONTEND, DevPathPolicy.ROLE_FRONTEND);
        assertThrows(
                PayrollException.class,
                () -> frontend.apply(DevEdit.write(BACKEND_FILE, "class A {}\n")));
        assertTrue(frontend.touchedFiles().isEmpty());
    }

    @Test
    @DisplayName("会话只接受补丁类产物类型")
    void rejectsNonPatchArtifactType() {
        assertThrows(
                PayrollException.class,
                () -> session(DevArtifactType.TEST_REPORT, DevPathPolicy.ROLE_IMPLEMENTER));
    }

    @Test
    @DisplayName("批量应用按顺序执行，任一失败即抛出")
    void applyAllRunsInOrder() {
        DevEditSession session = session(DevArtifactType.PATCH_BACKEND, DevPathPolicy.ROLE_IMPLEMENTER);
        List<DevSandboxEditor.Outcome> outcomes =
                session.applyAll(
                        List.of(
                                DevEdit.replace(BACKEND_FILE, "int v = 1;", "int v = 2;"),
                                DevEdit.write("payroll-common/src/main/java/B.java", "class B {}\n")));

        assertEquals(2, outcomes.size());
        assertEquals(
                List.of(BACKEND_FILE, "payroll-common/src/main/java/B.java"),
                session.touchedFiles());
        assertFalse(workspace.diff().isBlank());
    }

    @Test
    @DisplayName("补丁新增明文凭据时拒绝快照并记审计")
    void rejectsSecretsInPatch() {
        DevEditSession session = session(DevArtifactType.PATCH_BACKEND, DevPathPolicy.ROLE_IMPLEMENTER);
        session.apply(
                DevEdit.replace(
                        BACKEND_FILE,
                        "int v = 1;",
                        "String password = \"P@ssw0rd-prod-987654321\";"));

        PayrollException error = assertThrows(PayrollException.class, () -> session.snapshot("含密钥"));
        assertTrue(error.getMessage().contains("明文凭据"), error.getMessage());
        assertTrue(
                artifacts.latest("task-a", DevArtifactType.PATCH_BACKEND).isEmpty(),
                "命中凭据的补丁不得入库");
        AuditPage page =
                audit.query(
                        new AuditQuery(null, null, null, null, null, null, null, null, 1, 50));
        assertTrue(
                page.items().stream()
                        .anyMatch(item -> "devteam.patch.rejected".equals(item.action())));
    }

    private DevEditSession session(DevArtifactType type, String role) {
        return new DevEditSession(
                "task-a", type, "dev-backend", role, workspace, artifacts, audit);
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
