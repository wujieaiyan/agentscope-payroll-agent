package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.payroll.sandbox.SandboxDataService;
import io.agentscope.payroll.sandbox.SandboxDataWriteService;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SkillWorkspaceService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxDataWriteServiceTest {

    private static final String SKILL = "poetry";

    @TempDir
    Path temp;

    private SandboxSettings settings;
    private SandboxDataService data;
    private SandboxDataWriteService service;

    @BeforeEach
    void setUp() {
        settings = settings(SandboxDataWriteTestDefaults.FILE_BYTES, SandboxDataWriteTestDefaults.TOTAL_BYTES);
        data = new SandboxDataService(settings, mock(SkillWorkspaceService.class));
        service = new SandboxDataWriteService(settings, mock(SkillWorkspaceService.class));
    }

    @Test
    @DisplayName("上传后可浏览、预览，删除后文件消失且目录保留")
    void uploadListsPreviewsAndDeletes() throws Exception {
        byte[] content = "静夜思\n".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);

        SandboxDataWriteService.DataWriteResult uploaded =
                service.upload(
                        SKILL,
                        "poems/静夜思.md",
                        false,
                        content.length,
                        digest,
                        new ByteArrayInputStream(content));

        assertEquals("poems/静夜思.md", uploaded.path());
        assertEquals(content.length, uploaded.size());
        assertEquals(digest, uploaded.sha256());
        assertFalse(uploaded.overwritten());
        assertTrue(uploaded.modifiedAt() > 0);
        assertTrue(Files.isRegularFile(settings.skillDataDir(SKILL).resolve("poems/静夜思.md")));

        SandboxDataService.TreeResult shallow = data.tree(SKILL, null, 1);
        assertEquals(List.of("poems"), shallow.entries().stream()
                .map(SandboxDataService.Entry::name).toList());
        assertEquals("dir", shallow.entries().get(0).type());

        SandboxDataService.TreeResult deep = data.tree(SKILL, "", 2);
        assertEquals(
                List.of("poems", "poems/静夜思.md"),
                deep.entries().stream().map(SandboxDataService.Entry::path).toList());

        SandboxDataService.FileResult file = data.file(SKILL, "poems/静夜思.md", null);
        assertTrue(file.isText());
        assertEquals("静夜思\n", file.content());
        assertEquals("text/markdown", file.mime());

        service.delete(SKILL, "poems/静夜思.md");

        assertThrows(
                NoSuchFileException.class, () -> data.file(SKILL, "poems/静夜思.md", null));
        assertEquals(List.of("poems"), data.tree(SKILL, null, 1).entries().stream()
                .map(SandboxDataService.Entry::name).toList());
    }

    @Test
    @DisplayName("data/ 未创建时目录树为空、预览 404")
    void emptyBeforeFirstUpload() throws Exception {
        SandboxDataService.TreeResult tree = data.tree(SKILL, null, 1);

        assertEquals("", tree.path());
        assertTrue(tree.entries().isEmpty());
        assertFalse(tree.truncated());
        assertThrows(NoSuchFileException.class, () -> data.file(SKILL, "a.txt", null));
    }

    @Test
    @DisplayName("同名文件未声明覆盖返回冲突，覆盖后标记 overwritten")
    void rejectsConflictAndOverwrites() throws Exception {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two!".getBytes(StandardCharsets.UTF_8);
        service.upload(SKILL, "a.txt", false, first.length, sha256(first),
                new ByteArrayInputStream(first));

        assertThrows(
                SandboxDataWriteService.DataWriteConflictException.class,
                () -> service.upload(
                        SKILL, "a.txt", false, second.length, sha256(second),
                        new ByteArrayInputStream(second)));

        SandboxDataWriteService.DataWriteResult replaced =
                service.upload(
                        SKILL, "a.txt", true, second.length, sha256(second),
                        new ByteArrayInputStream(second));

        assertTrue(replaced.overwritten());
        assertEquals(second.length, replaced.size());
        assertEquals("two!", data.file(SKILL, "a.txt", null).content());
    }

    @Test
    @DisplayName("单文件与总量上限都在写入前拒绝")
    void enforcesQuotas() throws Exception {
        SandboxSettings tight = settings(4, 8);
        service = new SandboxDataWriteService(tight, mock(SkillWorkspaceService.class));
        data = new SandboxDataService(tight, mock(SkillWorkspaceService.class));
        byte[] four = "abcd".getBytes(StandardCharsets.UTF_8);
        byte[] five = "abcde".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                SandboxDataWriteService.DataWriteLimitException.class,
                () -> service.upload(SKILL, "big.txt", false, five.length, sha256(five),
                        new ByteArrayInputStream(five)));

        service.upload(SKILL, "a.txt", false, four.length, sha256(four),
                new ByteArrayInputStream(four));
        service.upload(SKILL, "b.txt", false, four.length, sha256(four),
                new ByteArrayInputStream(four));

        assertThrows(
                SandboxDataWriteService.DataWriteLimitException.class,
                () -> service.upload(SKILL, "c.txt", false, 1, sha256(new byte[] {'x'}),
                        new ByteArrayInputStream(new byte[] {'x'})));
        assertFalse(Files.exists(settings.skillDataDir(SKILL).resolve("c.txt")));
    }

    @Test
    @DisplayName("声明大小或摘要与正文不一致时拒绝且不留下临时文件")
    void verifiesSizeAndDigest() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] shorter = "hell".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(SKILL, "a.txt", false, content.length, sha256(content),
                        new ByteArrayInputStream(shorter)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(SKILL, "a.txt", false, content.length, sha256(shorter),
                        new ByteArrayInputStream(content)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(SKILL, "a.txt", false, content.length, sha256(content), null));

        Path root = settings.skillDataDir(SKILL);
        assertFalse(Files.exists(root.resolve("a.txt")));
        try (var children = Files.list(root)) {
            assertEquals(0, children.count(), "失败上传不应残留临时文件");
        }
    }

    @Test
    @DisplayName("非法路径与可执行后缀一律拒绝")
    void rejectsUnsafePathsAndDirectories() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);

        for (String bad : List.of("../evil.txt", "run.sh", "a:b.txt", "/abs.txt", "a\\b.txt")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.upload(SKILL, bad, false, content.length, digest,
                            new ByteArrayInputStream(content)),
                    bad);
        }
        service.upload(SKILL, "dir/a.txt", false, content.length, digest,
                new ByteArrayInputStream(content));
        assertThrows(
                IllegalArgumentException.class, () -> data.file(SKILL, "dir", null));
        assertThrows(
                IllegalArgumentException.class, () -> service.delete(SKILL, "dir"));
    }

    @Test
    @DisplayName("拒绝符号链接目录与链接文件")
    void rejectsSymlinks() throws Exception {
        Path root = settings.skillDataDir(SKILL);
        Files.createDirectories(root);
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        boolean linked;
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
            linked = true;
        } catch (UnsupportedOperationException | IOException e) {
            linked = false;
        }
        Assumptions.assumeTrue(linked, "当前文件系统不支持创建符号链接，跳过");

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(SKILL, "link/a.txt", false, content.length, sha256(content),
                        new ByteArrayInputStream(content)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(SKILL, "link.txt", false, content.length, sha256(content),
                        new ByteArrayInputStream(content)));
        assertThrows(
                IllegalArgumentException.class, () -> service.delete(SKILL, "link/secret.txt"));
        assertTrue(data.tree(SKILL, null, 1).entries().stream()
                .noneMatch(entry -> "link".equals(entry.name()) || "link.txt".equals(entry.name())));
    }

    @Test
    @DisplayName("技能 ID 不合法时直接拒绝")
    void rejectsInvalidSkillId() {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.upload("Poetry", "a.txt", false, content.length, sha256(content),
                        new ByteArrayInputStream(content)));
        assertThrows(IllegalArgumentException.class, () -> data.tree("Poetry", null, 1));
    }

    private SandboxSettings settings(long fileBytes, long totalBytes) {
        return new SandboxSettings(
                "token",
                "docker",
                "payroll-sandbox-runtime:local",
                4,
                temp.toString(),
                temp.resolve("skills").toString(),
                "payroll-sandbox-init:local",
                600_000,
                1024L * 1024 * 1024,
                2,
                fileBytes,
                totalBytes);
    }

    private static String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private static final class SandboxDataWriteTestDefaults {

        private static final long FILE_BYTES = 16 * 1024 * 1024;
        private static final long TOTAL_BYTES = 64 * 1024 * 1024;

        private SandboxDataWriteTestDefaults() {
        }
    }
}
