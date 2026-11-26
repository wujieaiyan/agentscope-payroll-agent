package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 解析产物落盘约定：{@code parsed/{documentId}/{sha256}/{profile}.json}，同目录写 manifest.json 与
 * CURRENT 指针；写入先落 .part 再原子改名，避免读到半个文件。
 */
public final class KbParseArtifacts {

    public static final String MANIFEST_FILE = "manifest.json";
    public static final String CURRENT_FILE = "CURRENT";

    private static final Pattern SAFE_PROFILE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private KbParseArtifacts() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 解析产物：自描述清单 + 全文与页区间。 */
    public record Artifact(
            long documentId,
            String sourceSha256,
            String profile,
            String ext,
            int pageCount,
            int charCount,
            int ocrPages,
            String engine,
            long ms,
            long createdAtEpochMs,
            KbParsedDocument document) {
    }

    public static String profile(ParseConfig config, int maxPages) {
        try {
            String canonical =
                    MAPPER.writeValueAsString(config == null ? ParseConfig.defaults() : config)
                            + "|maxPages="
                            + maxPages;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("解析配置指纹失败：" + e.getMessage(), e);
        }
    }

    public static Path artifactPath(Path root, long documentId, String sha256, String profile) {
        requireSafeProfile(profile);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("源文件哈希格式不正确");
        }
        return root.resolve(Long.toString(documentId)).resolve(sha256).resolve(profile + ".json");
    }

    public static Path existing(Path root, long documentId, String sha256, String profile) {
        Path path = artifactPath(root, documentId, sha256, profile);
        return Files.isRegularFile(path) ? path : null;
    }

    /** 把产物写入目标目录，返回稳定路径；同 profile 重复写会覆盖为新结果。 */
    public static Path writeAtomic(Path root, Artifact artifact) throws IOException {
        Path target =
                artifactPath(
                        root, artifact.documentId(), artifact.sourceSha256(), artifact.profile());
        Path directory = target.getParent();
        Files.createDirectories(directory);
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact);
        writeAtomic(target, json);
        writeAtomic(directory.resolve(MANIFEST_FILE), json);
        writeAtomic(
                directory.resolve(CURRENT_FILE),
                artifact.profile().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    public static Artifact read(Path file) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(file), Artifact.class);
    }

    public static Path resolveKey(Path root, String artifactKey) {
        if (artifactKey == null || artifactKey.isBlank()) {
            throw new KbParseException("解析服务未返回产物路径");
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(artifactKey).normalize();
        if (!target.startsWith(base)) {
            throw new KbParseException("解析产物路径越界：" + artifactKey);
        }
        return target;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                    DigestInputStream dis = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[64 * 1024];
                while (dis.read(buffer) >= 0) {
                    // 流式读取只为计算摘要
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用：" + e.getMessage(), e);
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.write(part, content);
        moveAtomic(part, target);
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireSafeProfile(String profile) {
        if (profile == null || !SAFE_PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("解析 profile 非法：" + profile);
        }
    }
}
