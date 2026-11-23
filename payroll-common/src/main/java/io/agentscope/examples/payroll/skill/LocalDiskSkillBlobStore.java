package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地磁盘上的内容寻址 blob 存储，布局 {@code <root>/<sha[0:2]>/<sha>}。
 *
 * <p>写路径是「临时文件 + {@code ATOMIC_MOVE}」：崩溃或并发只会留下 {@code .tmp} 残片，
 * 不会出现半个文件被当成正式资源读走。读路径重算 sha256 与文件名比对，坏块直接报错而不是
 * 返回脏字节——按规格 R1，缺失与损坏都不回落 Nacos。
 */
public final class LocalDiskSkillBlobStore implements SkillBlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskSkillBlobStore.class);

    public static final String DEFAULT_ROOT = "./data/skill-blobs";
    /** 与 {@link SkillFileSettings#DEFAULT_MAX_FILE_BYTES} 同值：切库第一天不放宽任何现网配额。 */
    public static final long DEFAULT_MAX_BLOB_BYTES = 512 * 1024L;

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final Path root;
    private final long maxBlobBytes;

    public LocalDiskSkillBlobStore(String root, long maxBlobBytes) {
        this.root =
                Paths.get(root == null || root.isBlank() ? DEFAULT_ROOT : root.trim())
                        .toAbsolutePath()
                        .normalize();
        this.maxBlobBytes = maxBlobBytes <= 0 ? DEFAULT_MAX_BLOB_BYTES : maxBlobBytes;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("创建技能资源存储目录失败：" + e.getMessage(), e);
        }
    }

    /** 装配用：{@code PAYROLL_SKILL_BLOB_ROOT} / {@code PAYROLL_SKILL_BLOB_MAX_BYTES}。 */
    public static LocalDiskSkillBlobStore fromEnvironment() {
        String root = System.getenv("PAYROLL_SKILL_BLOB_ROOT");
        return new LocalDiskSkillBlobStore(
                root == null || root.isBlank() ? DEFAULT_ROOT : root,
                longEnv("PAYROLL_SKILL_BLOB_MAX_BYTES", DEFAULT_MAX_BLOB_BYTES));
    }

    @Override
    public BlobRef put(byte[] content, String mediaType) {
        if (content == null) {
            throw new PayrollException("技能资源内容不能为空");
        }
        if (content.length > maxBlobBytes) {
            throw new PayrollException(
                    "技能资源超过单文件上限 " + maxBlobBytes + " 字节，当前 " + content.length + " 字节");
        }
        String sha = sha256(content);
        Path target = pathOf(sha);
        if (!Files.isRegularFile(target)) {
            write(target, content, sha);
        }
        return new BlobRef(sha, storageKey(sha), content.length, mediaType(mediaType));
    }

    @Override
    public Optional<byte[]> get(String sha256) {
        String expected = normalizeSha(sha256);
        Path path = pathOf(expected);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new PayrollException("读取技能资源失败：" + e.getMessage(), e);
        }
        String actual = sha256(content);
        if (!actual.equals(expected)) {
            log.error("[技能存储] blob 校验失败：期望 {}，实际 {}（{}）", expected, actual, path);
            throw new PayrollException("技能资源已损坏，请按指纹重新导入：" + expected);
        }
        return Optional.of(content);
    }

    @Override
    public Map<String, byte[]> getAll(Collection<String> sha256s) {
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (String sha256 : sha256s) {
            String expected = normalizeSha(sha256);
            if (content.containsKey(expected)) {
                continue;
            }
            get(expected).ifPresent(bytes -> content.put(expected, bytes));
        }
        return content;
    }

    @Override
    public boolean exists(String sha256) {
        String value = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        return SHA256_PATTERN.matcher(value).matches() && Files.isRegularFile(pathOf(value));
    }

    @Override
    public void delete(String sha256) {
        Path path = pathOf(sha256);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 走到这里通常已经是「事务失败后的补偿删除」，再抛会盖掉真正的失败原因；孤儿文件由保留期巡检收。
            log.warn("[技能存储] 删除 blob 失败：{}，原因={}", path, e.getMessage());
        }
    }

    @Override
    public String location() {
        return "local:" + root;
    }

    private void write(Path target, byte[] content, String sha) {
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), sha, ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AccessDeniedException raced) {
                // Windows 上并发把两份同名内容原子改名到同一目标，失败方拿到的是 ACCESS_DENIED 而不是
                // ALREADY_EXISTS；只有确认文件已在位才认「别人先落盘」，否则按写失败上抛。
                if (!Files.isRegularFile(target)) {
                    throw raced;
                }
                Files.deleteIfExists(temp);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new PayrollException("写入技能资源失败：" + e.getMessage(), e);
        }
    }

    /** sha 只来自 {@code skill_blob.sha256}，这里再校验一次，防一行脏数据变成越界路径。 */
    private Path pathOf(String sha) {
        String normalized = normalizeSha(sha);
        Path path = root.resolve(normalized.substring(0, 2)).resolve(normalized).normalize();
        if (!path.startsWith(root)) {
            throw new PayrollException("技能资源定位不合法");
        }
        return path;
    }

    private static String normalizeSha(String sha256) {
        String value = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(value).matches()) {
            throw new PayrollException("技能资源指纹不合法（需 64 位十六进制 sha256）");
        }
        return value;
    }

    private static String storageKey(String sha) {
        return sha.substring(0, 2) + "/" + sha;
    }

    private static String mediaType(String mediaType) {
        return mediaType == null || mediaType.isBlank() ? "text/plain" : mediaType.trim();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件残片不影响正确性，交给下次写同名 key 或巡检清理。
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是整数，当前值：" + value, e);
        }
    }
}
