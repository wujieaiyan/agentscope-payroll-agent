package io.agentscope.examples.payroll.sandbox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 技能数据浏览契约：目录树 / 文件预览的路径、参数上限与 HMAC 规范串。
 *
 * <p>查询参数会进入 HMAC 签名，因此客户端与沙箱必须用同一方法生成路径：
 * 参数按 key 字典序、值按 UTF-8 百分号编码，空路径也保留 {@code path=}，
 * 避免两侧各自拼接导致签名不一致或参数被篡改。
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
