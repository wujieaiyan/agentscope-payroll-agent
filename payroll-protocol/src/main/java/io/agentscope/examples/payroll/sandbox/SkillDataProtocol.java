package io.agentscope.examples.payroll.sandbox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 技能数据契约：目录树 / 文件预览 / 人工上传 / 删除的路径、参数上限与 HMAC 规范串。
 *
 * <p>查询参数会进入 HMAC 签名，因此客户端与沙箱必须用同一方法生成路径：
 * 参数按 key 字典序、值按 UTF-8 百分号编码，空路径也保留 {@code path=}，
 * 避免两侧各自拼接导致签名不一致或参数被篡改。
 *
 * <p>人工上传与初始化脚本写同一个 {@code data/} 目录，因此重建数据会连同上
 * 传文件一起清空；上传只允许普通数据文件，禁止可执行扩展名。
 */
public final class SkillDataProtocol {

    private static final int MAX_PATH_LENGTH = 200;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    /** 与 Windows 文件名/ADS 语义冲突的字符；沙箱虽跑 Linux，但本机直跑也复用它。 */
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("[:*?\"<>|]");

    public static final String TREE_SUFFIX = "/data/tree";
    public static final String FILE_SUFFIX = "/data/file";

    public static final int DEFAULT_DEPTH = 1;
    public static final int MAX_DEPTH = 3;
    public static final int MAX_ENTRIES = 500;

    public static final int DEFAULT_PREVIEW_BYTES = 256 * 1024;
    public static final int HARD_PREVIEW_BYTES = 1024 * 1024;

    public static final String QUERY_PATH = "path";
    public static final String QUERY_DEPTH = "depth";
    public static final String QUERY_MAX_BYTES = "maxBytes";
    public static final String QUERY_OVERWRITE = "overwrite";
    public static final String QUERY_SHA256 = "sha256";
    public static final String QUERY_SIZE = "size";

    /** 人工上传与 setup 产物共用 data/：单文件 200 MiB，默认总量 1 GiB、硬顶 5 GiB。 */
    public static final long MAX_UPLOAD_FILE_BYTES = 200L * 1024 * 1024;
    public static final long MAX_UPLOAD_TOTAL_BYTES = 1024L * 1024 * 1024;
    public static final long HARD_MAX_UPLOAD_TOTAL_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int MAX_UPLOAD_FILES = 5000;

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> FORBIDDEN_UPLOAD_SUFFIXES =
            Set.of(
                    "sh", "bash", "bat", "cmd", "exe", "dll", "so", "dylib", "com", "scr", "msi",
                    "ps1", "jar");

    private SkillDataProtocol() {
    }

    /** 目录树规范路径（含排序后的 query），用于发送与验签。 */
    public static String treePath(String skillId, String path, Integer depth) {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = requireDataPath(path, "数据目录");
        int effectiveDepth = requireDepth(depth);
        return base(id, TREE_SUFFIX)
                + canonicalQuery(
                        Map.of(
                                QUERY_PATH,
                                relative,
                                QUERY_DEPTH,
                                Integer.toString(effectiveDepth)));
    }

    /** 文件预览规范路径（含排序后的 query），path 不能为空。 */
    public static String filePath(String skillId, String path, Integer maxBytes) {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = requireDataPath(path, "数据文件");
        if (relative.isEmpty()) {
            throw new IllegalArgumentException("数据文件路径必填");
        }
        int effectiveMaxBytes = requireMaxBytes(maxBytes);
        return base(id, FILE_SUFFIX)
                + canonicalQuery(
                        Map.of(
                                QUERY_PATH,
                                relative,
                                QUERY_MAX_BYTES,
                                Integer.toString(effectiveMaxBytes)));
    }

    /**
     * 上传规范路径：与预览共用 {@code /data/file}，path、覆盖标志、声明大小与摘要全部参与签名，
     * 避免只签 path 时被篡改大小或摘要。
     */
    public static String uploadPath(
            String skillId, String path, boolean overwrite, long size, String sha256) {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = requireUploadFile(path, "数据文件");
        long effectiveSize = requireUploadSize(size);
        String digest = requireSha256(sha256);
        return base(id, FILE_SUFFIX)
                + canonicalQuery(
                        Map.of(
                                QUERY_PATH,
                                relative,
                                QUERY_OVERWRITE,
                                Boolean.toString(overwrite),
                                QUERY_SIZE,
                                Long.toString(effectiveSize),
                                QUERY_SHA256,
                                digest));
    }

    /** 删除规范路径：与预览共用 {@code /data/file}，按方法区分 GET/DELETE。 */
    public static String deletePath(String skillId, String path) {
        String id = SkillInitProtocol.requireSkillId(skillId);
        String relative = requireUploadFile(path, "数据文件");
        return base(id, FILE_SUFFIX) + canonicalQuery(Map.of(QUERY_PATH, relative));
    }

    /** 可写文件路径：非空，且禁止上传可执行或脚本类型。 */
    public static String requireUploadFile(String value, String label) {
        String path = requireDataPath(value, label);
        if (path.isEmpty()) {
            throw new IllegalArgumentException(label + "路径必填");
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (FORBIDDEN_UPLOAD_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException(
                    label + "不允许可执行或脚本类型（." + suffix + "）：" + path);
        }
        return path;
    }

    public static long requireUploadSize(long size) {
        if (size < 1 || size > MAX_UPLOAD_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "单文件大小需在 1–" + MAX_UPLOAD_FILE_BYTES + " 字节之间：" + size);
        }
        return size;
    }

    public static String requireSha256(String value) {
        String digest = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("内容摘要必须是 64 位十六进制 SHA-256");
        }
        return digest;
    }

    public static String base(String skillId, String suffix) {
        return SkillInitProtocol.STATUS_PATH_PREFIX + SkillInitProtocol.requireSkillId(skillId) + suffix;
    }

    /** 1–3，缺省 1；越界属于客户端错误。 */
    public static int requireDepth(Integer requested) {
        if (requested == null) {
            return DEFAULT_DEPTH;
        }
        if (requested < 1 || requested > MAX_DEPTH) {
            throw new IllegalArgumentException("目录深度只能是 1–" + MAX_DEPTH);
        }
        return requested;
    }

    /** 默认 256 KiB；超过 1 MiB 硬顶直接拒绝，不做静默截断。 */
    public static int requireMaxBytes(Integer requested) {
        if (requested == null) {
            return DEFAULT_PREVIEW_BYTES;
        }
        if (requested < 1 || requested > HARD_PREVIEW_BYTES) {
            throw new IllegalArgumentException(
                    "单次预览上限为 1–" + HARD_PREVIEW_BYTES + " 字节");
        }
        return requested;
    }

    /**
     * 数据浏览专用相对路径校验：允许中文、空格等在真实数据文件中常见的字符，
     * 但仍拒绝绝对路径、{@code ..} 穿越、反斜杠、控制字符与 Windows 保留字符。
     * 空串表示数据根目录。
     */
    public static String requireDataPath(String value, String label) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) {
            return "";
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(label + "路径不能超过 " + MAX_PATH_LENGTH + " 个字符");
        }
        if (path.indexOf('\\') >= 0 || CONTROL.matcher(path).find()) {
            throw new IllegalArgumentException(label + "路径不能包含反斜杠或控制字符：" + path);
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException(label + "路径必须是相对路径：" + path);
        }
        if (WINDOWS_RESERVED.matcher(path).find()) {
            throw new IllegalArgumentException(label + "路径不能包含 : * ? \" < > | 字符：" + path);
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + "路径不能包含空、. 或 .. 片段：" + path);
            }
        }
        return path;
    }

    /** 按 key 字典序编码参数；调用方负责传入已校验的值。 */
    public static String canonicalQuery(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
        return "?" + query;
    }

    /** 百分号编码；空格用 %20 而非 +，避免服务端把 + 解释成空格后再编码不一致。 */
    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
