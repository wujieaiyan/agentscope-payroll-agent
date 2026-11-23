package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevWorkspaceTest {

    @TempDir Path temp;

    @Test
    @DisplayName("克隆隔离工作区：记录基线 commit，diff 反映修改，关闭后清理目录")
    void cloneDiffsAndCleansUp() throws IOException {
        Path source = initRepo(temp.resolve("source"));
        Path target = temp.resolve("work/task-1");

        try (DevWorkspace workspace = DevWorkspace.clone(source, target)) {
            assertEquals(40, workspace.baselineCommit().length());
            assertTrue(Files.isDirectory(target.resolve(".git")));
            assertTrue(workspace.temporary());
            assertTrue(workspace.diff().isBlank());

            Files.writeString(target.resolve("a.txt"), "two\n");
            String diff = workspace.diff();

            assertTrue(diff.contains("--- a/a.txt"));
            assertTrue(diff.contains("-one"));
            assertTrue(diff.contains("+two"));
            assertEquals(1, workspace.changedFileCount());
        }
        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("工作区新建文件进入 diff（git add -A 后对比 HEAD）")
    void newFileAppearsInDiff() throws IOException {
        Path source = initRepo(temp.resolve("source"));
        try (DevWorkspace workspace = DevWorkspace.clone(source, temp.resolve("work/task-2"))) {
            Path created = workspace.root().resolve("payroll-common/src/main/java/New.java");
            Files.createDirectories(created.getParent());
            Files.writeString(created, "class New {}\n");

            String diff = workspace.diff();

            assertTrue(diff.contains("new file mode 100644"));
            assertTrue(diff.contains("+++ b/payroll-common/src/main/java/New.java"));
            assertTrue(diff.contains("+class New {}"));
        }
    }

    @Test
    @DisplayName("打开既有工作区不清理目录，基线取当前 HEAD")
    void openKeepsDirectory() throws IOException {
        Path existing = initRepo(temp.resolve("existing"));
        String head;
        try (Git git = Git.open(existing.toFile())) {
            head = git.getRepository().resolve("HEAD").name();
        }
        try (DevWorkspace workspace = DevWorkspace.open(existing)) {
            assertFalse(workspace.temporary());
            assertEquals(head, workspace.baselineCommit());
        }
        assertTrue(Files.isDirectory(existing.resolve(".git")));
    }

    @Test
    @DisplayName("非 Git 目录既不能克隆也不能打开")
    void rejectsNonRepository() throws IOException {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        assertThrows(
                PayrollException.class,
                () -> DevWorkspace.clone(plain, temp.resolve("work/task-4")));
        assertThrows(PayrollException.class, () -> DevWorkspace.open(plain));
    }

    @Test
    @DisplayName("目标目录已存在时拒绝克隆，避免覆盖他人工作")
    void refusesExistingTarget() throws IOException {
        Path source = initRepo(temp.resolve("source"));
        Path target = Files.createDirectories(temp.resolve("work/task-5"));
        assertThrows(PayrollException.class, () -> DevWorkspace.clone(source, target));
    }

    private static Path initRepo(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            Files.writeString(dir.resolve("a.txt"), "one\n");
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
}
