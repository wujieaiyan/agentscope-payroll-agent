package io.agentscope.payroll.parser;

import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析产物清理：{@code parsed/<documentId>/<sha256>/} 每个文档保留最新 2 个版本，
 * 其余超过 TTL 的目录删除；目录内 CURRENT 标记表示一次完整写入，缺失时按同规则回收。
 *
 * <p>只处理流水号文档目录与 64 位十六进制版本目录，其他文件不动；产物根目录不可写
 * （remote 模式下 Web 只读挂载）时由解析进程执行，Web 不参与。
 */
public final class ArtifactCleaner {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCleaner.class);

    static final int KEEP_VERSIONS = 2;
    private static final int MAX_DEPTH_GUARD = 3;

    private final Path root;
    private final int ttlDays;
    private final MeterRegistry registry;

    public ArtifactCleaner(Path root, int ttlDays, MeterRegistry registry) {
        this.root = root.toAbsolutePath().normalize();
        this.ttlDays = Math.max(0, ttlDays);
        this.registry = registry;
    }

    public record CleanupReport(int documents, int deletedVersions, int keptVersions, int failures) {}

    public CleanupReport cleanup() {
        if (!Files.isDirectory(root)) {
            return new CleanupReport(0, 0, 0, 0);
        }
        if (ttlDays <= 0) {
            log.info("[解析产物] 清理已关闭（artifact-ttl-days=0）");
            return new CleanupReport(0, 0, 0, 0);
        }
        long cutoffMillis =
                Instant.now().minus(ttlDays, ChronoUnit.DAYS).toEpochMilli();
        int documents = 0;
        int deleted = 0;
        int kept = 0;
        int failures = 0;
        try (Stream<Path> docDirs = Files.list(root)) {
            for (Path docDir : docDirs.toList()) {
                if (!Files.isDirectory(docDir) || !isNumeric(docDir.getFileName().toString())) {
                    continue;
                }
                documents++;
                List<Path> versions;
                try (Stream<Path> children = Files.list(docDir)) {
                    versions =
                            children.filter(Files::isDirectory)
                                    .filter(
                                            path ->
                                                    isSha256(
                                                            path.getFileName().toString()))
                                    .sorted(
                                            Comparator.comparingLong(
                                                            ArtifactCleaner::lastModified)
                                                    .reversed()
                                                    .thenComparing(
                                                            path ->
                                                                    path.getFileName()
                                                                            .toString()))
                                    .toList();
                }
                if (!versions.isEmpty() && currentMarker(versions.get(0)) == null) {
                    // 最新目录缺 CURRENT 说明写入中断：仍按「保留最新 2 个版本」兜底。
                    log.warn("[解析产物] 最新版本缺少 CURRENT 标记：{}", versions.get(0));
                }
                for (int i = 0; i < versions.size(); i++) {
                    Path version = versions.get(i);
                    if (i < KEEP_VERSIONS) {
                        kept++;
                        continue;
                    }
                    if (lastModified(version) >= cutoffMillis) {
                        kept++;
                        continue;
                    }
                    if (deleteRecursively(version)) {
                        deleted++;
                    } else {
                        failures++;
                        counter().increment();
                        log.warn("[解析产物] 删除失败：{}", version);
                    }
                }
            }
        } catch (IOException e) {
            failures++;
            counter().increment();
            log.warn("[解析产物] 清理失败：{}", e.getMessage());
        }
        log.info(
                "[解析产物] 清理完成：文档={}，删除版本={}，保留版本={}，失败={}，TTL={}天",
                documents,
                deleted,
                kept,
                failures,
                ttlDays);
        return new CleanupReport(documents, deleted, kept, failures);
    }

    private Counter counter() {
        return Counter.builder("payroll.kb.parse.cleanup.failures")
                .description("解析产物清理失败次数")
                .register(registry);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return true;
            }
            try (Stream<Path> walk = Files.walk(path, MAX_DEPTH_GUARD)) {
                List<Path> items =
                        walk.sorted(Comparator.reverseOrder()).toList();
                for (Path item : items) {
                    Files.deleteIfExists(item);
                }
            }
            return true;
        } catch (IOException e) {
            log.warn("[解析产物] 递归删除异常 {}：{}", path, e.getMessage());
            return false;
        }
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSha256(String value) {
        return value.matches("[0-9a-f]{64}");
    }

    static String currentMarker(Path versionDir) {
        Path marker = versionDir.resolve(KbParseArtifacts.CURRENT_FILE);
        return Files.isRegularFile(marker) ? marker.toString() : null;
    }
}
