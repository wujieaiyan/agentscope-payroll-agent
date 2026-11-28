package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.devteam.DevArtifactType;
import io.agentscope.examples.payroll.devteam.DevEditSession;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevWorkspace;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevSessionFilesystemTest {

    private static final String BACKEND_FILE = "payroll-common/src/main/java/A.java";
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("dev-user").sessionId("dev-session").build();

    @TempDir Path temp;
    private DevWorkspace workspace;
    private DevSessionFilesystem filesystem;

    @BeforeEach
    void setUp() throws IOException {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root.resolve("payroll-common/src/main/java"));
        Files.writeString(root.resolve(BACKEND_FILE), "class A {\n    int v = 1;\n}\n");
        initRepo(root);
        workspace = DevWorkspace.open(root);
        PayrollDatabase database = PayrollDatabase.inMemory("dev-fs-" + System.nanoTime());
        DevEditSession session =
                new DevEditSession(
                        "task-fs",
                        DevArtifactType.PATCH_BACKEND,
                        "dev-backend",
                        DevPathPolicy.ROLE_IMPLEMENTER,
                        workspace,
                        new DevArtifactStore(database),
                        new AuditService(PayrollMappers.forDatabase(database).auditStore(), 30));
        filesystem = new DevSessionFilesystem(DevPathPolicy.ROLE_IMPLEMENTER, root, session);
    }

    @AfterEach
    void tearDown() {
        workspace.close();
    }

    @Test
    @DisplayName("write_file/edit_file 落在工作区并计入会话")
    void writeAndEditReachWorkspace() throws IOException {
        WriteResult written =
                filesystem.write(
                        CONTEXT, "payroll-common/src/main/java/B.java", "class B {}\n");
        assertTrue(written.isSuccess());
        assertEquals("class B {}\n", Files.readString(workspace.root().resolve("payroll-common/src/main/java/B.java")));

        EditResult edited = filesystem.edit(CONTEXT, BACKEND_FILE, "int v = 1;", "int v = 9;", false);
        assertTrue(edited.isSuccess());
        assertEquals(1, edited.occurrences());
        assertTrue(Files.readString(workspace.root().resolve(BACKEND_FILE)).contains("int v = 9;"));
        assertEquals(2, filesystem.session().touchedFiles().size());
    }

    @Test
    @DisplayName("读取仍受只读护栏约束，写入后可读回")
    void readsGoThroughGuard() {
        filesystem.write(CONTEXT, "payroll-common/src/main/java/B.java", "class B {}\n");
        ReadResult read = filesystem.read(CONTEXT, "payroll-common/src/main/java/B.java", 0, 0);
        assertTrue(read.isSuccess());
        assertTrue(read.fileData().content().contains("class B {}"));
    }

    @Test
    @DisplayName("replaceAll 与越权路径都被拒绝")
    void deniesReplaceAllAndOutOfScope() {
        EditResult all = filesystem.edit(CONTEXT, BACKEND_FILE, "int", "long", true);
        assertFalse(all.isSuccess());
        assertTrue(all.error().contains("replaceAll"));

        WriteResult denied = filesystem.write(CONTEXT, "frontend/src/App.vue", "<template/>");
        assertFalse(denied.isSuccess());
        assertFalse(Files.exists(workspace.root().resolve("frontend/src/App.vue")));
    }

    @Test
    @DisplayName("越权写入登记护栏命中；replaceAll、未命中 oldText 等业务失败不登记")
    void guardRecordsAccessDenialsOnly() {
        DevGuardMonitor guard = new DevGuardMonitor();
        DevSessionFilesystem guarded =
                new DevSessionFilesystem(
                        DevPathPolicy.ROLE_IMPLEMENTER, workspace.root(), filesystem.session(), guard);

        assertFalse(guarded.edit(CONTEXT, BACKEND_FILE, "int", "long", true).isSuccess());
        assertFalse(guarded.edit(CONTEXT, BACKEND_FILE, "no-such-text", "x", false).isSuccess());
        assertFalse(guarded.uploadFiles(CONTEXT, List.of(Map.entry("a.txt", new byte[] {1})))
                .get(0)
                .isSuccess());
        assertFalse(guard.breached(), guard.events().toString());

        assertFalse(guarded.write(CONTEXT, "frontend/src/App.vue", "<template/>").isSuccess());
        assertTrue(guard.breached());
        assertEquals(1, guard.count(), guard.events().toString());
        assertTrue(guard.events().get(0).startsWith("write:frontend/src/App.vue"), guard.events().toString());

        assertFalse(guarded.write(CONTEXT, ".git/config", "[core]").isSuccess());
        assertEquals(2, guard.count(), guard.events().toString());
    }

    @Test
    @DisplayName("上传、下载、删除与移动一律拒绝")
    void deniesUnsupportedOperations() {
        List<FileUploadResponse> uploads =
                filesystem.uploadFiles(CONTEXT, List.of(Map.entry("a.txt", new byte[] {1})));
        assertFalse(uploads.get(0).isSuccess());
        List<FileDownloadResponse> downloads =
                filesystem.downloadFiles(CONTEXT, List.of("a.txt"));
        assertFalse(downloads.get(0).isSuccess());
        assertFalse(filesystem.delete(CONTEXT, "a.txt").isSuccess());
        assertFalse(filesystem.move(CONTEXT, "a.txt", "b.txt").isSuccess());
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
