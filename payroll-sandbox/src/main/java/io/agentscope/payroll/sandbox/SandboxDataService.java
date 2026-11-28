package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 技能数据浏览：目录树与文本预览。
 *
 * <p>只访问 {@code <skillRoot>/<id>/data}，路径由技能 ID 推导并逐段校验：
 * 规范化后必须在根目录内，最终 realpath 也必须在根目录内，任何符号链接与非常规文件一律拒绝。
 * 数据既可能来自 setup 产物，也可能来自人工上传，因此未初始化但有文件的目录同样可浏览；
 * 初始化中返回 409，避免读到半成品。
 */
@Service
public class SandboxDataService {

    private static final Logger log = LoggerFactory.getLogger(SandboxDataService.class);

    private static final int DETECT_BYTES = 8 * 1024;
    private static final String UTF_8 = "utf-8";

    private static final Set<String> TEXT_SUFFIXES =
            Set.of(
                    "md", "txt", "json", "yaml", "yml", "csv", "tsv", "log",
                    "py", "sh", "js", "xml", "html", "htm", "ini", "toml", "sql");

    private static final Map<String, String> MIME_BY_SUFFIX =
            Map.ofEntries(
                    Map.entry("json", "application/json"),
                    Map.entry("yaml", "application/yaml"),
                    Map.entry("yml", "application/yaml"),
                    Map.entry("csv", "text/csv"),
                    Map.entry("tsv", "text/tab-separated-values"),
                    Map.entry("md", "text/markdown"),
                    Map.entry("txt", "text/plain"),
                    Map.entry("log", "text/plain"),
                    Map.entry("xml", "application/xml"),
                    Map.entry("html", "text/html"),
                    Map.entry("htm", "text/html"),
                    Map.entry("py", "text/x-python"),
                    Map.entry("sh", "application/x-sh"),
                    Map.entry("js", "text/javascript"),
                    Map.entry("png", "image/png"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("gif", "image/gif"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("pdf", "application/pdf"),
                    Map.entry("zip", "application/zip"),
                    Map.entry("gz", "application/gzip"),
                    Map.entry("parquet", "application/vnd.apache.parquet"));

    private static final Comparator<Path> ENTRY_ORDER =
            Comparator.comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .thenComparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER);

    private final SandboxSettings settings;
    private final SkillWorkspaceService workspace;

    public SandboxDataService(SandboxSettings settings, SkillWorkspaceService workspace) {
        this.settings = settings;
        this.workspace = workspace;
    }

    public TreeResult tree(String skillId, String path, Integer depth) throws IOException {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = SkillDataProtocol.requireDataPath(path, "数据目录");
        int levels = SkillDataProtocol.requireDepth(depth);
        requireBrowsable(id);
        Path root = settings.skillDataDir(id);
        if (relative.isEmpty() && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            // 还没有 setup 产物或人工上传：data/ 尚未创建，按空目录返回，页面可直接开始上传。
            return new TreeResult(id, "", List.of(), false, SkillDataProtocol.MAX_ENTRIES);
        }
        return treeAt(root, id, relative, levels);
    }

    public FileResult file(String skillId, String path, Integer maxBytes) throws IOException {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = SkillDataProtocol.requireDataPath(path, "数据文件");
        if (relative.isEmpty()) {
            throw new IllegalArgumentException("数据文件路径必填");
        }
        int limit = SkillDataProtocol.requireMaxBytes(maxBytes);
        requireBrowsable(id);
        return fileAt(settings.skillDataDir(id), relative, limit);
    }

    /** 通用目录树：调用方已验证相对路径与状态门禁。 */
    TreeResult treeAt(Path root, String skillId, String relative, int levels)
            throws IOException {
        Path rootReal = requireRoot(root);
        Path dir = resolve(root, rootReal, relative);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(label(relative));
        }
        List<Entry> entries = new ArrayList<>();
        boolean[] truncated = {false};
        collect(dir, relative, levels, entries, truncated);
        return new TreeResult(skillId, relative, List.copyOf(entries), truncated[0],
                SkillDataProtocol.MAX_ENTRIES);
    }

    /** 通用文件预览；调用方负责路径规则与状态门禁。 */
    FileResult fileAt(Path root, String relative, int limit) throws IOException {
        Path rootReal = requireRoot(root);
        Path target = resolve(root, rootReal, relative);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("只能预览普通文件：" + relative);
        }
        long size = Files.size(target);
        long modifiedAt = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis();
        int fetch = (int) Math.min(size, Math.max((long) limit, (long) DETECT_BYTES));
        byte[] data = readBytes(target, fetch);
        int detectLength = Math.min(data.length, DETECT_BYTES);
        boolean text =
                hasTextSuffix(target)
                        && noNul(data, detectLength)
                        && decodes(data, detectLength, size > detectLength);
        boolean truncated = size > limit;
        String content = null;
        if (text) {
            int contentLength = Math.min(data.length, limit);
            int end = truncated ? completePrefixLength(data, contentLength) : contentLength;
            try {
                content = strictDecode(data, end);
            } catch (CharacterCodingException e) {
                log.debug("[技能数据] 文件前缀不是合法 UTF-8：{}", relative);
                text = false;
            }
        }
        return new FileResult(
                relative,
                size,
                modifiedAt,
                text,
                text ? UTF_8 : null,
                truncated,
                text ? content : null,
                mimeOf(target));
    }

    public record Entry(
            String name, String path, String type, long size, long modifiedAt, Boolean isText) {
    }

    public record TreeResult(
            String skillId,
            String path,
            List<Entry> entries,
            boolean truncated,
            int entryLimit) {
    }

    public record FileResult(
            String path,
            long size,
            long modifiedAt,
            boolean isText,
            String encoding,
            boolean truncated,
            String content,
            String mime) {
    }

    /** 初始化进行中：浏览器应提示等待，而不是展示半成品或假空树。 */
    public static final class DataNotReadyException extends RuntimeException {

        public DataNotReadyException(String message) {
            super(message);
        }
    }

    private void requireBrowsable(String skillId) {
        SkillInitStatus state = workspace.status(skillId);
        String status = state == null ? null : state.status();
        if (SkillInitStatus.INITIALIZING.equals(status)) {
            throw new DataNotReadyException("技能数据正在初始化，请等待完成后再浏览");
        }
    }

    private static Path requireRoot(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("技能数据目录不存在");
        }
        return dataDir.toRealPath();
    }

    private static Path resolve(Path dataDir, Path rootReal, String relative) throws IOException {
        if (relative.isEmpty()) {
            return rootReal;
        }
        Path candidate = dataDir.resolve(relative).normalize();
        if (!candidate.startsWith(dataDir)) {
            throw new IllegalArgumentException("数据路径越界：" + relative);
        }
        Path current = dataDir;
        for (String segment : relative.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("数据目录不支持符号链接：" + relative);
            }
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(rootReal)) {
            throw new IllegalArgumentException("数据路径越界：" + relative);
        }
        return real;
    }

    private static void collect(
            Path dir,
            String relative,
            int remainingDepth,
            List<Entry> entries,
            boolean[] truncated)
            throws IOException {
        if (entries.size() >= SkillDataProtocol.MAX_ENTRIES) {
            truncated[0] = true;
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.sorted(ENTRY_ORDER).toList();
        }
        for (Path child : children) {
            if (entries.size() >= SkillDataProtocol.MAX_ENTRIES) {
                truncated[0] = true;
                return;
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(
                        child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            if (attrs.isSymbolicLink()) {
                continue;
            }
            String name = child.getFileName().toString();
            String childPath = relative.isEmpty() ? name : relative + "/" + name;
            if (!isBrowsablePath(childPath)) {
                continue;
            }
            if (attrs.isDirectory()) {
                entries.add(new Entry(name, childPath, "dir", 0,
                        attrs.lastModifiedTime().toMillis(), null));
                if (remainingDepth > 1) {
                    collect(child, childPath, remainingDepth - 1, entries, truncated);
                }
            } else if (attrs.isRegularFile()) {
                entries.add(new Entry(name, childPath, "file", attrs.size(),
                        attrs.lastModifiedTime().toMillis(), isText(child, attrs.size())));
            }
        }
    }

    private static boolean isBrowsablePath(String relative) {
        try {
            SkillDataProtocol.requireDataPath(relative, "数据文件");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isText(Path file, long size) {
        if (!hasTextSuffix(file)) {
            return false;
        }
        int length = (int) Math.min(size, DETECT_BYTES);
        if (length <= 0) {
            return true;
        }
        try {
            byte[] prefix = readBytes(file, length);
            return noNul(prefix, prefix.length) && decodes(prefix, prefix.length, size > length);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasTextSuffix(Path file) {
        return TEXT_SUFFIXES.contains(suffixOf(file));
    }

    private static String mimeOf(Path file) {
        return MIME_BY_SUFFIX.getOrDefault(suffixOf(file), "application/octet-stream");
    }

    private static String suffixOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean noNul(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean decodes(byte[] bytes, int length, boolean mayEndMidChar) {
        int end = mayEndMidChar ? completePrefixLength(bytes, length) : length;
        try {
            strictDecode(bytes, end);
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String strictDecode(byte[] bytes, int length) throws CharacterCodingException {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    /** 截断点若落在多字节序列中间，回退到该字符的起始位置。 */
    private static int completePrefixLength(byte[] bytes, int length) {
        int index = length - 1;
        while (index >= 0 && (bytes[index] & 0xC0) == 0x80) {
            index--;
        }
        if (index < 0) {
            return length;
        }
        int lead = bytes[index] & 0xFF;
        int need =
                lead < 0x80
                        ? 1
                        : (lead >> 5) == 0x06
                                ? 2
                                : (lead >> 4) == 0x0E
                                        ? 3
                                        : (lead >> 3) == 0x1E ? 4 : 0;
        if (need == 0) {
            return length;
        }
        return length - index < need ? index : length;
    }

    private static byte[] readBytes(Path file, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[length];
        try (FileChannel channel =
                FileChannel.open(
                        file,
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            return buffer.position() == length ? data : Arrays.copyOf(data, buffer.position());
        }
    }

    private static String label(String relative) {
        return relative.isEmpty() ? "." : relative;
    }
}
