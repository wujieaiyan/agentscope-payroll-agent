package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevSandboxEditorTest {

    private static final String BACKEND_FILE = "payroll-common/src/main/java/A.java";

    @TempDir Path root;

    private DevSandboxEditor editor(String role) {
        return new DevSandboxEditor(role, root);
    }

    @Test
    @DisplayName("整文件写入：自动建目录并返回前后 hash")
    void createsFileWithParents() throws IOException {
        DevSandboxEditor.Outcome outcome =
                editor(DevPathPolicy.ROLE_IMPLEMENTER)
                        .apply(DevEdit.write(BACKEND_FILE, "class A {}\n"));

        assertEquals(DevSandboxEditor.Change.CREATED, outcome.change());
        assertEquals("", outcome.beforeHash());
        assertTrue(outcome.afterHash().length() == 64);
        assertEquals(0, outcome.beforeBytes());
        assertEquals("class A {}\n", Files.readString(root.resolve(BACKEND_FILE)));
    }

    @Test
    @DisplayName("整文件写入已有文件：MODIFIED 且保留新内容")
    void modifiesExistingFile() throws IOException {
        Path target = root.resolve(BACKEND_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old\n");
        DevSandboxEditor.Outcome outcome =
                editor(DevPathPolicy.ROLE_IMPLEMENTER).apply(DevEdit.write(BACKEND_FILE, "new\n"));

        assertEquals(DevSandboxEditor.Change.MODIFIED, outcome.change());
        assertTrue(!outcome.beforeHash().isBlank());
        assertEquals("new\n", Files.readString(target));
    }

    @Test
    @DisplayName("写入相同内容返回 UNCHANGED，不触碰文件")
    void identicalWriteIsNoop() throws IOException {
        Path target = root.resolve(BACKEND_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "same\n");
        DevSandboxEditor.Outcome outcome =
                editor(DevPathPolicy.ROLE_IMPLEMENTER).apply(DevEdit.write(BACKEND_FILE, "same\n"));

        assertEquals(DevSandboxEditor.Change.UNCHANGED, outcome.change());
        assertEquals(outcome.beforeHash(), outcome.afterHash());
    }

    @Test
    @DisplayName("唯一匹配替换成功；缺失或多处匹配都拒绝")
    void replacesOnlyUniqueMatch() throws IOException {
        Path target = root.resolve(BACKEND_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "alpha beta alpha2\n");
        DevSandboxEditor editor = editor(DevPathPolicy.ROLE_IMPLEMENTER);

        editor.apply(DevEdit.replace(BACKEND_FILE, "beta", "gamma"));
        assertEquals("alpha gamma alpha2\n", Files.readString(target));

        assertThrows(
                PayrollException.class,
                () -> editor.apply(DevEdit.replace(BACKEND_FILE, "missing", "x")));
        Files.writeString(target, "same same\n");
        assertThrows(
                PayrollException.class,
                () -> editor.apply(DevEdit.replace(BACKEND_FILE, "same", "x")));
        assertEquals("same same\n", Files.readString(target));
    }

    @Test
    @DisplayName("替换不存在的文件直接拒绝")
    void replaceMissingFileRejected() {
        assertThrows(
                PayrollException.class,
                () ->
                        editor(DevPathPolicy.ROLE_IMPLEMENTER)
                                .apply(DevEdit.replace(BACKEND_FILE, "a", "b")));
    }

    @Test
    @DisplayName("路径穿越、绝对路径与黑名单文件一律拒绝")
    void rejectsIllegalPaths() {
        DevSandboxEditor editor = editor(DevPathPolicy.ROLE_IMPLEMENTER);
        assertThrows(PayrollException.class, () -> editor.apply(DevEdit.write("../escape.txt", "x")));
        assertThrows(PayrollException.class, () -> editor.apply(DevEdit.write("/etc/passwd", "x")));
        assertThrows(
                PayrollException.class,
                () -> editor.apply(DevEdit.write("payroll-common/src/main/java/.env", "x")));
    }

    @Test
    @DisplayName("角色白名单：前端不能写后端，实现者不能写前端，QA 无写权限")
    void enforcesRoleWhitelist() {
        assertThrows(
                PayrollException.class,
                () ->
                        editor(DevPathPolicy.ROLE_FRONTEND)
                                .apply(DevEdit.write(BACKEND_FILE, "x")));
        assertThrows(
                PayrollException.class,
                () ->
                        editor(DevPathPolicy.ROLE_IMPLEMENTER)
                                .apply(DevEdit.write("frontend/src/App.vue", "<template/>")));
        assertThrows(
                PayrollException.class,
                () -> editor(DevPathPolicy.ROLE_QA).apply(DevEdit.write(BACKEND_FILE, "x")));
        assertEquals(
                "frontend/src/App.vue",
                editor(DevPathPolicy.ROLE_FRONTEND).normalize("frontend/src/App.vue"));
    }

    @Test
    @DisplayName("二进制内容（含 NUL）与超限内容拒绝写入")
    void rejectsBinaryAndOversize() {
        DevSandboxEditor editor = editor(DevPathPolicy.ROLE_IMPLEMENTER);
        assertThrows(PayrollException.class, () -> editor.apply(DevEdit.write(BACKEND_FILE, "a\0b")));
        char[] huge = new char[DevSandboxEditor.MAX_CONTENT_BYTES + 1];
        java.util.Arrays.fill(huge, 'a');
        assertThrows(
                PayrollException.class,
                () -> editor.apply(DevEdit.write(BACKEND_FILE, new String(huge))));
    }

    @Test
    @DisplayName("符号链接组件禁止写入，防止借链接逃逸")
    void rejectsSymlinkComponent() throws IOException {
        Path outside = Files.createTempDirectory("dev-outside");
        try {
            Path link = root.resolve("payroll-common");
            Files.createDirectories(root.resolve("payroll-common"));
            Files.deleteIfExists(link);
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException e) {
                Assumptions.assumeTrue(false, "当前账户无法创建符号链接");
            }
            DevSandboxEditor editor = editor(DevPathPolicy.ROLE_IMPLEMENTER);
            assertThrows(
                    PayrollException.class,
                    () -> editor.apply(DevEdit.write("payroll-common/src/main/java/A.java", "x")));
        } finally {
            deleteQuietly(outside);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Test
    @DisplayName("读取接口沿用写权限校验：只读角色与无权限角色都拒绝")
    void readRespectsRole() {
        assertThrows(PayrollException.class, () -> editor(DevPathPolicy.ROLE_PRODUCT).read("pom.xml"));
        assertThrows(PayrollException.class, () -> editor(DevPathPolicy.ROLE_ARCHITECT).read("pom.xml"));
    }
}
