package io.agentscope.payroll.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 解析产物清理：每文档保留最新 2 个版本，超过 TTL 的旧版本删除，CURRENT 保护当前版本。 */
class ArtifactCleanerTest {

    @TempDir Path root;

    @Test
    @DisplayName("10 个版本目录保留最新 2 个，超过 TTL 的旧版本删除且不动其他文件")
    void keepsLatestTwoVersionsAndDeletesExpired() throws IOException {
        Path doc = Files.createDirectories(root.resolve("42"));
        long now = System.currentTimeMillis();
        List<Path> versions = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Path version = Files.createDirectories(doc.resolve(hash(i)));
            boolean recent = i >= 8;
            Files.setLastModifiedTime(
                    version,
                    FileTime.fromMillis(now - (recent ? 3_600_000L : 30L * 86_400_000L)));
            versions.add(version);
        }
        Files.writeString(versions.get(9).resolve(KbParseArtifacts.CURRENT_FILE), "yes");

        Path noiseFile = Files.writeString(root.resolve("notes.txt"), "keep");
        Path nonNumericDoc = Files.createDirectories(root.resolve("abc").resolve(hash(20)));
        Path badVersion = Files.createDirectories(doc.resolve("not-a-hash"));

        ArtifactCleaner.CleanupReport report =
                new ArtifactCleaner(root, 7, new SimpleMeterRegistry()).cleanup();

        assertEquals(1, report.documents());
        assertEquals(8, report.deletedVersions());
        assertEquals(2, report.keptVersions());
        assertEquals(0, report.failures());
        assertTrue(Files.isDirectory(versions.get(8)));
        assertTrue(Files.isDirectory(versions.get(9)));
        assertTrue(Files.isRegularFile(versions.get(9).resolve(KbParseArtifacts.CURRENT_FILE)));
        for (int i = 0; i < 8; i++) {
            assertFalse(Files.exists(versions.get(i)), "旧版本应被删除：" + versions.get(i));
        }
        assertTrue(Files.exists(noiseFile));
        assertTrue(Files.isDirectory(nonNumericDoc));
        assertTrue(Files.isDirectory(badVersion));
    }

    @Test
    @DisplayName("最新两个版本始终保留，即使已超过 TTL")
    void keepsTwoNewestEvenWhenExpired() throws IOException {
        Path doc = Files.createDirectories(root.resolve("7"));
        for (int i = 0; i < 3; i++) {
            Path version = Files.createDirectories(doc.resolve(hash(i)));
            Files.setLastModifiedTime(
                    version, FileTime.fromMillis(System.currentTimeMillis() - 30L * 86_400_000L));
        }

        ArtifactCleaner.CleanupReport report =
                new ArtifactCleaner(root, 7, new SimpleMeterRegistry()).cleanup();

        assertEquals(1, report.deletedVersions());
        assertEquals(2, report.keptVersions());
        assertTrue(Files.isDirectory(doc.resolve(hash(0))) || Files.isDirectory(doc.resolve(hash(1))));
        try (var children = Files.list(doc)) {
            assertEquals(2, children.filter(Files::isDirectory).count());
        }
    }

    @Test
    @DisplayName("根目录缺失或 TTL=0 时不做任何删除")
    void missingRootAndDisabledTtlAreNoops() throws IOException {
        ArtifactCleaner.CleanupReport missing =
                new ArtifactCleaner(root.resolve("absent"), 7, new SimpleMeterRegistry()).cleanup();
        assertEquals(0, missing.documents());
        assertEquals(0, missing.deletedVersions());

        Path doc = Files.createDirectories(root.resolve("9"));
        Path version = Files.createDirectories(doc.resolve(hash(5)));
        Files.setLastModifiedTime(
                version, FileTime.fromMillis(System.currentTimeMillis() - 30L * 86_400_000L));

        ArtifactCleaner.CleanupReport disabled =
                new ArtifactCleaner(root, 0, new SimpleMeterRegistry()).cleanup();
        assertEquals(0, disabled.deletedVersions());
        assertTrue(Files.isDirectory(version));
    }

    private static String hash(int index) {
        return "%064x".formatted(index);
    }
}
