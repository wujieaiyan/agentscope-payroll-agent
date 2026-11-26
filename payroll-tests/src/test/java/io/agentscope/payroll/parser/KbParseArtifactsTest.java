package io.agentscope.payroll.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.agentscope.examples.payroll.kb.parse.KbParseException;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KbParseArtifactsTest {

    private static final String SHA = "a".repeat(64);

    @TempDir Path tempDir;

    @Test
    @DisplayName("产物写入后可按 documentId+sha+profile 定位，并留下 manifest 与 CURRENT")
    void writesManifestAndCurrent() throws Exception {
        KbParseArtifacts.Artifact artifact = artifact("p123", 3);

        Path written = KbParseArtifacts.writeAtomic(tempDir, artifact);

        assertTrue(Files.isRegularFile(written));
        assertEquals("p123.json", written.getFileName().toString());
        Path directory = written.getParent();
        assertEquals("p123", Files.readString(directory.resolve(KbParseArtifacts.CURRENT_FILE)).strip());
        assertTrue(Files.isRegularFile(directory.resolve(KbParseArtifacts.MANIFEST_FILE)));
        assertTrue(Files.notExists(directory.resolve("p123.json.part")));
        assertEquals(
                written.toAbsolutePath().normalize(),
                KbParseArtifacts.existing(tempDir, 7L, SHA, "p123").toAbsolutePath().normalize());
        assertEquals(artifact.document(), KbParseArtifacts.read(written).document());
    }

    @Test
    @DisplayName("profile 由配置与页数上限决定，配置变化即失效缓存")
    void profileIsStableAndConfigSensitive() {
        ParseConfig defaults = ParseConfig.defaults();
        assertEquals(
                KbParseArtifacts.profile(defaults, 200),
                KbParseArtifacts.profile(ParseConfig.defaults(), 200));
        assertNotEquals(
                KbParseArtifacts.profile(defaults, 200),
                KbParseArtifacts.profile(defaults, 100));
        ParseConfig changed = ParseConfig.defaults();
        changed.options.filterHeaderFooter = false;
        assertNotEquals(
                KbParseArtifacts.profile(defaults, 200),
                KbParseArtifacts.profile(changed, 200));
    }

    @Test
    @DisplayName("artifactKey 不能跳出产物根目录")
    void resolveKeyRejectsTraversal() {
        assertThrows(
                KbParseException.class,
                () -> KbParseArtifacts.resolveKey(tempDir, "../escape.json"));
        assertNotNull(KbParseArtifacts.resolveKey(tempDir, "7/" + SHA + "/p123.json"));
    }

    @Test
    @DisplayName("损坏的产物可被 existing 定位但 read 抛错，交由上层回源重解析")
    void corruptArtifactDetected() throws Exception {
        Path written = KbParseArtifacts.writeAtomic(tempDir, artifact("pcorrupt", 1));
        Files.writeString(written, "not-json", StandardCharsets.UTF_8);

        assertNotNull(KbParseArtifacts.existing(tempDir, 7L, SHA, "pcorrupt"));
        assertThrows(Exception.class, () -> KbParseArtifacts.read(written));
    }

    @Test
    @DisplayName("非法 profile 与哈希格式在拼接路径前即被拒绝")
    void rejectsUnsafePathSegments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> KbParseArtifacts.artifactPath(tempDir, 7L, SHA, "../evil"));
        assertThrows(
                IllegalArgumentException.class,
                () -> KbParseArtifacts.artifactPath(tempDir, 7L, "short", "p123"));
        assertNull(KbParseArtifacts.existing(tempDir, 7L, SHA, "missing"));
    }

    private static KbParseArtifacts.Artifact artifact(String profile, int pageCount) {
        KbParsedDocument document =
                new KbParsedDocument(
                        "第一页\n\n第二页",
                        pageCount,
                        List.of(new KbParsedDocument.PageRange(1, 0, 3)),
                        List.of());
        return new KbParseArtifacts.Artifact(
                7L, SHA, profile, "pdf", pageCount, document.text().length(), 0, "native", 12L,
                System.currentTimeMillis(), document);
    }
}
