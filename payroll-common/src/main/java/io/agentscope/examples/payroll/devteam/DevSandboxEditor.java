package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 隔离工作区内的结构化编辑器（P1-1）：所有写入先过角色白名单与黑名单，再复核真实路径，
 * 最后原子落盘；返回前后 hash 供审计与 G2 的 diff 漂移检测。
 *
 * <p>只接受仓库内相对路径；任何已存在的符号链接组件、目录目标、二进制内容（含 NUL）
 * 与超过 4MB 的文本都直接拒绝，失败时不留下半写文件。
 */
public final class DevSandboxEditor {

    public static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;

    public enum Change {
        CREATED,
        MODIFIED,
        UNCHANGED
    }

    /** 一次编辑的结果；{@code beforeHash} 在新建文件时为空串。 */
    public record Outcome(
            String path,
            Change change,
            String beforeHash,
            String afterHash,
            long beforeBytes,
            long afterBytes) {}

    private final String role;
    private final Path root;
    private final Path realRoot;

    public DevSandboxEditor(String role, Path sandboxRoot) {
        if (DevPathPolicy.role(role).isEmpty()) {
            throw new PayrollException("未知的研发角色：" + role);
        }
        if (sandboxRoot == null) {
            throw new PayrollException("沙箱工作区不能为空");
        }
        this.role = role;
        this.root = sandboxRoot.toAbsolutePath().normalize();
        this.realRoot = realPathOrSelf(this.root);
    }

    public String role() {
        return role;
    }

    public Path root() {
        return root;
    }

    /** 校验写权限并返回仓库内相对路径；供会话在编辑前记录基线内容。 */
    public String normalize(String path) {
        DevPathPolicy.Outcome outcome = DevPathPolicy.evaluate(role, path, true);
        if (!outcome.allowed()) {
            throw new DevAccessDeniedException("拒绝写入：" + outcome.reason());
        }
        return outcome.path();
    }

    public synchronized Outcome apply(DevEdit edit) {
        if (edit == null) {
            throw new PayrollException("编辑内容不能为空");
        }
        String relative = normalize(edit.path());
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new DevAccessDeniedException("拒绝写入：路径超出沙箱工作区：" + relative);
        }
        rejectSymlinkComponents(target, relative);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PayrollException("拒绝写入：目标是目录：" + relative);
        }

        Optional<String> before = readText(target, relative);
        String after =
                switch (edit.kind()) {
                    case WRITE -> edit.content();
                    case REPLACE -> replaceOnce(relative, before, edit);
                };
        if (before.isPresent() && before.get().equals(after)) {
            return new Outcome(
                    relative,
                    Change.UNCHANGED,
                    sha256(before.get()),
                    sha256(after),
                    before.get().getBytes(StandardCharsets.UTF_8).length,
                    after.getBytes(StandardCharsets.UTF_8).length);
        }
        byte[] bytes = validateContent(relative, after);
        writeAtomically(target, relative, bytes);
        return new Outcome(
                relative,
                before.isPresent() ? Change.MODIFIED : Change.CREATED,
                before.map(DevSandboxEditor::sha256).orElse(""),
                sha256(after),
                before.map(value -> (long) value.getBytes(StandardCharsets.UTF_8).length)
                        .orElse(0L),
                bytes.length);
    }

    /** 读取文件内容；不存在返回空，大小/编码不合规时拒绝。 */
    public Optional<String> read(String path) {
        String relative = normalize(path);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new DevAccessDeniedException("拒绝访问：路径超出沙箱工作区：" + relative);
        }
        rejectSymlinkComponents(target, relative);
        return readText(target, relative);
    }

    private static String replaceOnce(String relative, Optional<String> before, DevEdit edit) {
        String source =
                before.orElseThrow(() -> new PayrollException("拒绝替换：文件不存在：" + relative));
        int first = source.indexOf(edit.oldText());
        if (first < 0) {
            throw new PayrollException("替换失败：未找到 oldText：" + relative);
        }
        if (source.indexOf(edit.oldText(), first + edit.oldText().length()) >= 0) {
            throw new PayrollException(
                    "替换失败：oldText 在 " + relative + " 中出现多次，请提供更精确的上下文");
        }
        return source.substring(0, first) + edit.newText() + source.substring(first + edit.oldText().length());
    }

    private Optional<String> readText(Path target, String relative) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PayrollException("拒绝访问：不是普通文件：" + relative);
        }
        try {
            long size = Files.size(target);
            if (size > MAX_CONTENT_BYTES) {
                throw new PayrollException(
                        "拒绝访问：文件超过 " + (MAX_CONTENT_BYTES / (1024 * 1024)) + "MB：" + relative);
            }
            String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            if (content.indexOf('\0') >= 0) {
                throw new PayrollException("拒绝访问：仅支持文本文件：" + relative);
            }
            return Optional.of(content);
        } catch (IOException e) {
            throw new PayrollException("读取文件失败：" + relative + "，原因：" + e.getMessage(), e);
        }
    }

    private static byte[] validateContent(String relative, String content) {
        if (content.indexOf('\0') >= 0) {
            throw new PayrollException("拒绝写入：仅支持文本文件：" + relative);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new PayrollException(
                    "拒绝写入：内容超过 " + (MAX_CONTENT_BYTES / (1024 * 1024)) + "MB：" + relative);
        }
        return bytes;
    }

    /** 拒绝目标路径上任何已存在的符号链接组件，防止借链接逃逸沙箱。 */
    private void rejectSymlinkComponents(Path target, String relative) {
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new DevAccessDeniedException("拒绝写入：路径包含符号链接：" + relative);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
        }
        try {
            Path real = target.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new DevAccessDeniedException("拒绝写入：真实路径超出沙箱工作区：" + relative);
            }
        } catch (IOException e) {
            throw new DevAccessDeniedException("拒绝写入：无法解析真实路径：" + relative, e);
        }
    }

    private static void writeAtomically(Path target, String relative, byte[] bytes) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, ".dev-edit-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new PayrollException("写入文件失败：" + relative + "，原因：" + e.getMessage(), e);
        }
    }

    private static Path realPathOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
