package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 技能人工数据写入：把上传文件落到与 setup 相同的 {@code <skillRoot>/<id>/data}。
 *
 * <p>上传先写同目录临时文件，校验声明大小与 SHA-256 后再原子替换，失败不留下半成品。
 * 初始化进行中拒绝写入，避免与 setup 重建 data/ 竞争；重建数据会把上传文件一并清空。
 */
@Service
public class SandboxDataWriteService {

    private static final int BUFFER_BYTES = 64 * 1024;

    private final SandboxSettings settings;
    private final SkillWorkspaceService workspace;

    public SandboxDataWriteService(SandboxSettings settings, SkillWorkspaceService workspace) {
        this.settings = settings;
        this.workspace = workspace;
    }

    public DataWriteResult upload(
            String skillId,
            String path,
            boolean overwrite,
            long size,
            String sha256,
            InputStream body)
            throws IOException {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = SkillDataProtocol.requireUploadFile(path, "数据文件");
        long declaredSize = SkillDataProtocol.requireUploadSize(size);
        String digest = SkillDataProtocol.requireSha256(sha256);
        requireWritable(id);
        if (declaredSize > settings.uploadMaxFileBytes()) {
            throw new DataWriteLimitException(
                    "单文件上限为 " + settings.uploadMaxFileBytes() + " 字节：" + relative);
        }
        if (body == null) {
            throw new IllegalArgumentException("上传内容为空");
        }
        Path root = prepareRoot(settings.skillDataDir(id));
        Path target = prepareTarget(root, relative);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (existed) {
            if (Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("数据路径不是普通文件：" + relative);
            }
            if (!overwrite) {
                throw new DataWriteConflictException("数据文件已存在，请确认覆盖：" + relative);
            }
        }
        long existingSize = existed ? Files.size(target) : 0;
        Quota quota = measure(root);
        long projectedBytes = quota.bytes - existingSize + declaredSize;
        if (projectedBytes > settings.uploadMaxBytes()) {
            throw new DataWriteLimitException(
                    "数据目录总量上限为 " + settings.uploadMaxBytes() + " 字节");
        }
        long projectedFiles = quota.files - (existed ? 1 : 0) + 1;
        if (projectedFiles > SkillDataProtocol.MAX_UPLOAD_FILES) {
            throw new DataWriteLimitException(
                    "数据目录文件数上限为 " + SkillDataProtocol.MAX_UPLOAD_FILES);
        }
        String actual = writeVerified(target, declaredSize, digest, body);
        long modifiedAt = Files.getLastModifiedTime(target).toMillis();
        return new DataWriteResult(relative, declaredSize, modifiedAt, actual, existed);
    }

    public DataWriteResult delete(String skillId, String path) throws IOException {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = SkillDataProtocol.requireUploadFile(path, "数据文件");
        requireWritable(id);
        Path root = settings.skillDataDir(id);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("技能数据目录不存在");
        }
        Path rootReal = root.toRealPath();
        Path target = resolveExisting(root, rootReal, relative);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("数据路径不是普通文件：" + relative);
        }
        long size = Files.size(target);
        long modifiedAt = Files.getLastModifiedTime(target).toMillis();
        Files.delete(target);
        return new DataWriteResult(relative, size, modifiedAt, null, false);
    }

    public record DataWriteResult(
            String path, long size, long modifiedAt, String sha256, boolean overwritten) {
    }

    /** 同名文件已存在且未声明覆盖。 */
    public static final class DataWriteConflictException extends RuntimeException {

        public DataWriteConflictException(String message) {
            super(message);
        }
    }

    /** 超过单文件、总量或文件数上限。 */
    public static final class DataWriteLimitException extends RuntimeException {

        public DataWriteLimitException(String message) {
            super(message);
        }
    }

    private void requireWritable(String skillId) {
        SkillInitStatus state = workspace.status(skillId);
        String status = state == null ? null : state.status();
        if (SkillInitStatus.INITIALIZING.equals(status)) {
            throw new SandboxDataService.DataNotReadyException(
                    "技能数据正在初始化，请等待完成后再写入");
        }
    }

    private static Path prepareRoot(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("数据目录不是普通目录");
            }
        } else {
            Files.createDirectories(root);
            makeReadable(root, true);
        }
        return root.toRealPath();
    }

    private static Path prepareTarget(Path root, String relative) throws IOException {
        Path dir = root;
        String[] segments = relative.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            Path next = dir.resolve(segments[i]);
            requireNotSymlink(next, relative);
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("数据路径的上级不是目录：" + relative);
                }
            } else {
                Files.createDirectory(next);
                makeReadable(next, true);
            }
            dir = next;
        }
        Path parentReal = dir.toRealPath();
        if (!parentReal.startsWith(root)) {
            throw new IllegalArgumentException("数据路径越界：" + relative);
        }
        return parentReal.resolve(segments[segments.length - 1]);
    }

    private static Path resolveExisting(Path root, Path rootReal, String relative)
            throws IOException {
        Path current = root;
        for (String segment : relative.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("数据目录不支持符号链接：" + relative);
            }
        }
        Path real = current.toRealPath();
        if (!real.startsWith(rootReal)) {
            throw new IllegalArgumentException("数据路径越界：" + relative);
        }
        return real;
    }

    private static String writeVerified(
            Path target, long declaredSize, String digest, InputStream body) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
        try {
            MessageDigest sha256 = sha256();
            long total = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (InputStream in = body;
                    OutputStream out =
                            Files.newOutputStream(
                                    temp,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > declaredSize) {
                        throw new IllegalArgumentException("上传内容超过声明大小");
                    }
                    sha256.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (total != declaredSize) {
                throw new IllegalArgumentException("上传内容大小与声明不一致");
            }
            String actual = HexFormat.of().formatHex(sha256.digest());
            if (!actual.equals(digest)) {
                throw new IllegalArgumentException("上传内容摘要与声明不一致");
            }
            makeReadable(temp, false);
            move(temp, target);
            return actual;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(
                    temp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Quota measure(Path root) throws IOException {
        long[] total = {0, 0};
        try (var walk = Files.walk(root)) {
            walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(
                            path -> {
                                try {
                                    total[0] += Files.size(path);
                                    total[1]++;
                                } catch (IOException ignored) {
                                    // 文件在统计过程中被删除，忽略
                                }
                            });
        }
        return new Quota(total[0], total[1]);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static void requireNotSymlink(Path path, String label) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("数据目录不支持符号链接：" + label);
        }
    }

    private static void makeReadable(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            directory ? "rwxr-xr-x" : "rw-r--r--"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 等非 POSIX 文件系统不支持，容器挂载仍可读取
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响结果
        }
    }

    private record Quota(long bytes, long files) {
    }
}
