package io.agentscope.examples.payroll.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能初始化契约：路径、包内容白名单、超时与数据配额。
 *
 * <p>初始化容器允许联网并写入工作区，因此两侧都做完整校验：技能 ID 固定格式，
 * 只接受根目录固定文件与 {@code scripts/**}，setup 只能指向包内脚本，
 * 数据上限有硬顶，避免第三方仓库把沙箱磁盘写满。
 */
public final class SkillInitProtocol {

    public static final String INIT_PATH = "/internal/skills/init";
    public static final String STATUS_PATH_PREFIX = "/internal/skills/";

    public static final int DEFAULT_TIMEOUT_MS = 600_000;
    public static final int HARD_TIMEOUT_MS = 900_000;
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long HARD_MAX_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int MAX_FILES = 200;
    public static final int MAX_FILE_BYTES = 524_288;
    public static final int MAX_TOTAL_BYTES = 4_194_304;
    public static final int MAX_LOG_BYTES = 8 * 1024;
    public static final int MAX_SETUP_CHARS = 4_096;
    public static final String SETUP_SCRIPT = "setup.sh";

    private static final int MAX_PATH_LENGTH = 200;
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern PATH_SEGMENTS =
            Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Set<String> ROOT_FILES =
            Set.of("SKILL.md", "PROMPT.md", "README.md", "LICENSE", SETUP_SCRIPT);
    private static final List<String> SCRIPT_SUFFIXES = List.of(".py", ".sh", ".js");

    private SkillInitProtocol() {
    }

    public static String requireSkillId(String value) {
        String id = value == null ? "" : value.trim();
        if (!SKILL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法技能 ID：" + value);
        }
        return id;
    }

    public static String statusPath(String skillId) {
        return STATUS_PATH_PREFIX + requireSkillId(skillId);
    }

    /** setup 只能指向根目录 setup.sh 或 scripts/** 下的脚本。 */
    public static String requireSetupPath(String value) {
        String path = requireRelativePath(value, "初始化脚本");
        if (SETUP_SCRIPT.equals(path)) {
            return path;
        }
        if (!path.startsWith("scripts/")) {
            throw new IllegalArgumentException("初始化脚本只能是 setup.sh 或 scripts/**：" + path);
        }
        if (SCRIPT_SUFFIXES.stream().noneMatch(path::endsWith)) {
            throw new IllegalArgumentException("初始化脚本只支持 .py / .sh / .js：" + path);
        }
        return path;
    }

    /** 请求可携带根目录固定文件与 scripts/**；data/、references/ 与 assets/ 不参与初始化。 */
    public static String requireFilePath(String value) {
        String path = requireRelativePath(value, "包内文件");
        if (!isInitPath(path)) {
            throw new IllegalArgumentException(
                    "初始化只携带根目录固定文件与 scripts/** 下的脚本：" + path);
        }
        return path;
    }

    /** 判断包内文件是否参与初始化；非法路径一律视为不参与。 */
    public static boolean isInitPath(String value) {
        String path;
        try {
            path = requireRelativePath(value, "包内文件");
        } catch (IllegalArgumentException e) {
            return false;
        }
        return ROOT_FILES.contains(path)
                || (path.startsWith("scripts/")
                        && SCRIPT_SUFFIXES.stream().anyMatch(path::endsWith));
    }

    public static List<String> interpreters(String setupPath) {
        return interpreters(setupPath, null);
    }

    /**
     * 按脚本首行 shebang 选择解释器：声明 bash 的脚本用 /bin/bash，其余 .sh 固定 /bin/sh。
     *
     * <p>第三方技能常以 {@code #!/bin/bash} 加 {@code BASH_SOURCE} 等 bash 语法编写，
     * 统一用 sh 执行会在 dash/ash 下报 bad substitution。
     */
    public static List<String> interpreters(String setupPath, String setupContent) {
        if (setupPath.endsWith(".py")) {
            return List.of("python3", "-B");
        }
        if (setupPath.endsWith(".sh")) {
            return declaresBash(setupContent) ? List.of("/bin/bash") : List.of("/bin/sh");
        }
        return List.of("node");
    }

    private static boolean declaresBash(String setupContent) {
        if (setupContent == null || setupContent.isEmpty()) {
            return false;
        }
        int end = setupContent.indexOf('\n');
        String first = (end < 0 ? setupContent : setupContent.substring(0, end)).trim();
        if (!first.startsWith("#!")) {
            return false;
        }
        for (String token : first.substring(2).trim().split("\\s+")) {
            String name = token.substring(token.lastIndexOf('/') + 1);
            if ("bash".equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static int requireTimeout(Integer requested, int fallback) {
        int base = fallback <= 0 ? DEFAULT_TIMEOUT_MS : fallback;
        int value = requested == null || requested <= 0 ? base : requested;
        return Math.min(value, HARD_TIMEOUT_MS);
    }

    public static long requireMaxBytes(Long requested, long fallback) {
        long base = fallback <= 0 ? DEFAULT_MAX_BYTES : fallback;
        long value = requested == null || requested <= 0 ? base : requested;
        return Math.min(value, HARD_MAX_BYTES);
    }

    /** 返回每个文件的 UTF-8 字节数；路径、重复项与总量在这里一次性校验。 */
    public static List<Integer> validateFiles(List<PathAndBytes> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("初始化文件最多 " + MAX_FILES + " 个");
        }
        List<Integer> sizes = new ArrayList<>(files.size());
        Set<String> seen = new java.util.HashSet<>();
        int total = 0;
        for (PathAndBytes file : files) {
            if (file == null) {
                throw new IllegalArgumentException("包内文件不能为空");
            }
            String path = requireFilePath(file.path());
            if (!seen.add(path)) {
                throw new IllegalArgumentException("包内文件路径重复：" + path);
            }
            int size = file.bytes();
            if (size < 0) {
                throw new IllegalArgumentException("包内文件大小非法：" + path);
            }
            if (size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "单个文件不能超过 " + MAX_FILE_BYTES + " 字节：" + path);
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("初始化包总量不能超过 " + MAX_TOTAL_BYTES + " 字节");
            }
            sizes.add(size);
        }
        return List.copyOf(sizes);
    }

    public record PathAndBytes(String path, int bytes) {
    }

    private static String requireRelativePath(String value, String label) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException(label + "路径必填");
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
        if (!PATH_SEGMENTS.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    label + "路径只能包含字母、数字、点、下划线、短横线和斜杠：" + path);
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + "路径不能包含 . 或 .. 片段：" + path);
            }
        }
        return path;
    }
}
