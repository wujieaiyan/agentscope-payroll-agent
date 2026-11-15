package io.agentscope.examples.payroll.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * agent-runtime 与 sandbox-service 之间的执行契约：路径、参数、大小与超时上限。
 *
 * <p>两侧各自校验，沙箱侧不信任运行期传入的任何路径或参数；脚本只允许
 * {@code scripts/**.{py,sh,js}}，参数按数组传递，不接受 shell 字符串。
 */
public final class SandboxProtocol {

    public static final String PATH = "/internal/sandbox/execute";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int MAX_TIMEOUT_MS = 10_000;
    public static final int MAX_ARGS = 20;
    public static final int MAX_ARG_CHARS = 200;
    public static final int MAX_SCRIPTS = 20;
    public static final int MAX_SCRIPT_BYTES = 65_536;
    public static final int MAX_SCRIPTS_BYTES = 262_144;
    public static final int MAX_OUTPUT_BYTES = 8 * 1024;

    private static final int MAX_PATH_LENGTH = 200;
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern PATH_SEGMENTS =
            Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final String SCRIPTS_PREFIX = "scripts/";
    private static final List<String> SCRIPT_SUFFIXES = List.of(".py", ".sh", ".js");

    private SandboxProtocol() {
    }

    public static String requireSkillId(String value) {
        String id = value == null ? "" : value.trim();
        if (!SKILL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法技能 ID：" + value);
        }
        return id;
    }

    public static String requireScriptPath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("脚本路径必填");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("脚本路径不能超过 " + MAX_PATH_LENGTH + " 个字符");
        }
        if (path.indexOf('\\') >= 0 || CONTROL.matcher(path).find()) {
            throw new IllegalArgumentException("脚本路径不能包含反斜杠或控制字符：" + path);
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("脚本路径必须是相对路径：" + path);
        }
        if (!PATH_SEGMENTS.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "脚本路径只能包含字母、数字、点、下划线、短横线和斜杠：" + path);
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("脚本路径不能包含 . 或 .. 片段：" + path);
            }
        }
        if (!path.startsWith(SCRIPTS_PREFIX)) {
            throw new IllegalArgumentException("只能执行 scripts/ 目录下的脚本：" + path);
        }
        if (SCRIPT_SUFFIXES.stream().noneMatch(path::endsWith)) {
            throw new IllegalArgumentException("脚本只支持 .py / .sh / .js：" + path);
        }
        return path;
    }

    /** 参数逐个校验后返回不可变列表；不允许 NUL 与换行，避免污染日志或跨行注入。 */
    public static List<String> requireArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        if (args.size() > MAX_ARGS) {
            throw new IllegalArgumentException("脚本参数最多 " + MAX_ARGS + " 个");
        }
        List<String> clean = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) {
                throw new IllegalArgumentException("脚本参数不能为 null");
            }
            if (arg.length() > MAX_ARG_CHARS) {
                throw new IllegalArgumentException(
                        "单个脚本参数不能超过 " + MAX_ARG_CHARS + " 个字符");
            }
            if (arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("脚本参数不能包含 NUL 或换行");
            }
            clean.add(arg);
        }
        return List.copyOf(clean);
    }

    public static int requireTimeout(int requested) {
        if (requested <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.min(requested, MAX_TIMEOUT_MS);
    }

    /** 解释器固定，不接受模型指定；脚本路径已校验后缀。 */
    public static List<String> interpreter(String scriptPath) {
        if (scriptPath.endsWith(".py")) {
            return List.of("python3", "-B");
        }
        if (scriptPath.endsWith(".sh")) {
            return List.of("/bin/sh");
        }
        return List.of("node");
    }

    public static String scriptSha256(String content) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
