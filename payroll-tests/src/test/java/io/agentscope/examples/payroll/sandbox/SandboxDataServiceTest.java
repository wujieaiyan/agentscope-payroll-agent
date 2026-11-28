package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.payroll.sandbox.SandboxDataService;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SkillInitStatus;
import io.agentscope.payroll.sandbox.SkillWorkspaceService;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxDataServiceTest {

    private static final String SKILL = "poetry";

    @TempDir
    Path temp;

    private final SkillWorkspaceService workspace = mock(SkillWorkspaceService.class);
    private SandboxDataService service;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new SandboxDataService(
                        new SandboxSettings("token", "docker", "runtime", 2, temp.toString()),
                        workspace);
        when(workspace.status(SKILL)).thenReturn(ready());
        Files.createDirectories(dataDir().resolve("reports"));
    }

    @Test
    @DisplayName("根目录先列子目录再列文件，并按 1 层懒加载")
    void listsDirectoriesFirstAtOneLevel() throws Exception {
        Files.writeString(dataDir().resolve("b.txt"), "b");
        Files.writeString(dataDir().resolve("a.txt"), "a");
        Files.writeString(dataDir().resolve("reports/2024.csv"), "y,m");

        SandboxDataService.TreeResult tree = service.tree(SKILL, null, null);

        assertEquals("", tree.path());
        assertEquals(
                List.of("reports", "a.txt", "b.txt"),
                tree.entries().stream().map(SandboxDataService.Entry::name).toList());
        assertEquals("dir", tree.entries().get(0).type());
        assertEquals("file", tree.entries().get(1).type());
        assertEquals(Boolean.TRUE, tree.entries().get(1).isText());
        assertFalse(tree.truncated());
        assertEquals(SkillDataProtocol.MAX_ENTRIES, tree.entryLimit());
    }

    @Test
    @DisplayName("depth=2 递归展开子目录，路径以 / 拼接")
    void recursesToRequestedDepth() throws Exception {
        Files.writeString(dataDir().resolve("reports/2024.csv"), "y,m");

        SandboxDataService.TreeResult one = service.tree(SKILL, null, 1);
        assertEquals(1, one.entries().size());

        SandboxDataService.TreeResult two = service.tree(SKILL, "", 2);
        assertEquals(
                List.of("reports", "reports/2024.csv"),
                two.entries().stream().map(SandboxDataService.Entry::path).toList());
    }

    @Test
    @DisplayName("文本文件按 UTF-8 预览，二进制只回元数据")
    void previewsTextAndMetadataOnlyForBinary() throws Exception {
        Files.writeString(dataDir().resolve("a.txt"), "你好，世界");
        Files.write(dataDir().resolve("a.bin"), new byte[] {0, 1, 2, 3});

        SandboxDataService.FileResult text = service.file(SKILL, "a.txt", null);
        assertTrue(text.isText());
        assertEquals("utf-8", text.encoding());
        assertEquals("你好，世界", text.content());
        assertEquals("text/plain", text.mime());
        assertFalse(text.truncated());

        SandboxDataService.FileResult binary = service.file(SKILL, "a.bin", null);
        assertFalse(binary.isText());
        assertNull(binary.encoding());
        assertNull(binary.content());
        assertEquals("application/octet-stream", binary.mime());
    }

    @Test
    @DisplayName("截断点落在多字节字符中间时回退到完整字符边界")
    void truncatesAtUtf8Boundary() throws Exception {
        Files.writeString(dataDir().resolve("poem.md"), "你好世界");

        SandboxDataService.FileResult result = service.file(SKILL, "poem.md", 4);

        assertTrue(result.truncated());
        assertEquals(12, result.size());
        assertEquals("你", result.content());
    }

    @Test
    @DisplayName("后缀为文本但含 NUL 或非 UTF-8 字节时按二进制处理")
    void rejectsNulAndInvalidUtf8() throws Exception {
        Files.write(dataDir().resolve("nul-check.txt"), new byte[] {'a', 0, 'b'});
        Files.write(dataDir().resolve("bad.txt"), new byte[] {(byte) 0xFF, (byte) 0xFE});

        assertFalse(service.file(SKILL, "nul-check.txt", null).isText());
        assertFalse(service.file(SKILL, "bad.txt", null).isText());
    }

    @Test
    @DisplayName("越界、符号链接与目录预览一律拒绝")
    void rejectsEscapesAndNonFiles() throws Exception {
        Files.writeString(dataDir().resolve("a.txt"), "a");
        assertThrows(
                IllegalArgumentException.class, () -> service.tree(SKILL, "../etc", null));
        assertThrows(
                IllegalArgumentException.class, () -> service.file(SKILL, "a.txt/..", null));
        assertThrows(
                IllegalArgumentException.class, () -> service.file(SKILL, "reports", null));
        assertThrows(
                IllegalArgumentException.class, () -> service.file(SKILL, "", null));

        Path secret = Files.writeString(temp.resolve("secret.txt"), "secret");
        Path link = dataDir().resolve("link.txt");
        boolean linked;
        try {
            Files.createSymbolicLink(link, secret);
            linked = true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            linked = false;
        }
        Assumptions.assumeTrue(linked, "当前文件系统不支持创建符号链接，跳过");
        assertThrows(
                IllegalArgumentException.class, () -> service.file(SKILL, "link.txt", null));
        assertTrue(service.tree(SKILL, null, 1).entries().stream()
                .noneMatch(entry -> "link.txt".equals(entry.name())));
    }

    @Test
    @DisplayName("未初始化但有文件仍可浏览，初始化中 409，失败状态仍可排障")
    void mapsWorkspaceStates() throws Exception {
        when(workspace.status(SKILL)).thenReturn(SkillInitStatus.unknown(SKILL));
        assertEquals(1, service.tree(SKILL, null, 1).entries().size());
        assertThrows(
                NoSuchFileException.class, () -> service.tree(SKILL, "missing", 1));

        when(workspace.status(SKILL))
                .thenReturn(SkillInitStatus.initializing(SKILL, "setup.sh", 1L));
        assertThrows(
                SandboxDataService.DataNotReadyException.class, () -> service.tree(SKILL, null, 1));

        Files.writeString(dataDir().resolve("partial.txt"), "partial");
        when(workspace.status(SKILL))
                .thenReturn(
                        new SkillInitStatus(
                                SkillInitStatus.FAILED, SKILL, "setup.sh", 2, 1, 2, 0, "boom", "失败"));
        assertEquals(2, service.tree(SKILL, null, 1).entries().size());
    }

    @Test
    @DisplayName("data 目录尚未创建时返回空树，页面可直接开始上传")
    void missingDataDirIsEmptyTree() throws Exception {
        Files.delete(dataDir().resolve("reports"));
        Files.delete(dataDir());

        SandboxDataService.TreeResult tree = service.tree(SKILL, null, 1);

        assertEquals("", tree.path());
        assertTrue(tree.entries().isEmpty());
        assertFalse(tree.truncated());
    }

    @Test
    @DisplayName("超过 500 条时截断并标记 truncated")
    void capsEntryCount() throws Exception {
        for (int i = 0; i < 505; i++) {
            Files.writeString(dataDir().resolve("f" + i + ".txt"), "x");
        }

        SandboxDataService.TreeResult tree = service.tree(SKILL, null, 1);

        assertEquals(SkillDataProtocol.MAX_ENTRIES, tree.entries().size());
        assertTrue(tree.truncated());
    }

    @Test
    @DisplayName("中文与带空格的生成文件名照常列出并可单独预览")
    void listsChineseAndSpaceNames() throws Exception {
        Files.writeString(dataDir().resolve("正常.txt"), "ok");
        Files.writeString(dataDir().resolve("with space.txt"), "x");

        SandboxDataService.TreeResult tree = service.tree(SKILL, null, 1);

        assertEquals(
                List.of("reports", "with space.txt", "正常.txt"),
                tree.entries().stream().map(SandboxDataService.Entry::name).toList());
        assertEquals("ok", service.file(SKILL, "正常.txt", null).content());
        assertEquals("x", service.file(SKILL, "with space.txt", null).content());
    }

    @Test
    @DisplayName("旧版 imports 目录不再特殊处理，按普通目录列出")
    void legacyImportsDirIsOrdinary() throws Exception {
        Path legacy = dataDir().resolve("imports");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("manual.csv"), "y,m");
        Files.writeString(dataDir().resolve("a.txt"), "a");

        SandboxDataService.TreeResult tree = service.tree(SKILL, null, 2);

        assertTrue(
                tree.entries().stream()
                        .anyMatch(entry -> "imports/manual.csv".equals(entry.path())));
        assertTrue(
                tree.entries().stream()
                        .anyMatch(entry -> "a.txt".equals(entry.path())));
    }

    private Path dataDir() {
        return temp.resolve("skills").resolve(SKILL).resolve("data");
    }

    private static SkillInitStatus ready() {
        return new SkillInitStatus(
                SkillInitStatus.READY, SKILL, "setup.sh", 0, 1, 2, 0, "", "初始化完成");
    }
}
