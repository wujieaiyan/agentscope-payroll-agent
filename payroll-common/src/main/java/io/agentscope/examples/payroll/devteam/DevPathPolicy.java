package io.agentscope.examples.payroll.devteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 开发域路径策略（A5/D4）：按角色限定可读范围与可写白名单，黑名单始终优先。
 *
 * <p>白名单使用相对仓库根的 glob：{@code **} 匹配任意层目录，{@code *} 只匹配单段内字符。
 * 绝对路径、盘符、{@code ..} 穿越与空路径一律拒绝；{@code .git/}、{@code data/} 与密钥文件
 * 即使命中白名单也不放行，用于 fail-closed 与审计留痕。
 *
 * <p>P0-6：内置 payroll 仓库 profile 只是默认值。接入其他仓库时通过
 * {@code payroll.dev-team.paths-file}（或环境变量 {@code PAYROLL_DEV_PATH_PROFILE}）
 * 指定 JSON profile，覆盖各角色的读写 glob，无需改代码；黑名单与路径归一化规则不可被覆盖。
 */
public final class DevPathPolicy {

    public static final String ENV_PROFILE = "PAYROLL_DEV_PATH_PROFILE";

    public static final String ROLE_PRODUCT = "project-product";
    public static final String ROLE_ARCHITECT = "project-architect";
    public static final String ROLE_MANAGER = "project-manager";
    public static final String ROLE_IMPLEMENTER = "project-implementer";
    public static final String ROLE_FRONTEND = "project-frontend";
    public static final String ROLE_DBA = "project-dba";
    public static final String ROLE_DEVOPS = "project-devops";
    public static final String ROLE_QA = "project-qa";
    public static final String ROLE_REVIEWER = "project-reviewer";

    private static final String FRONTEND_READ = "frontend/src/**";
    private static final String FRONTEND_PUBLIC = "frontend/public/**";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GLOB_LENGTH = 200;

    private static final Map<String, RolePaths> DEFAULT_ROLES =
            Map.of(
                    ROLE_PRODUCT,
                    new RolePaths(ROLE_PRODUCT, false, List.of(), List.of()),
                    ROLE_ARCHITECT,
                    new RolePaths(ROLE_ARCHITECT, true, List.of("**"), List.of()),
                    ROLE_MANAGER,
                    new RolePaths(ROLE_MANAGER, true, List.of("**"), List.of()),
                    ROLE_IMPLEMENTER,
                    new RolePaths(
                            ROLE_IMPLEMENTER, true, List.of("**"), List.of("payroll-*/src/**")),
                    ROLE_FRONTEND,
                    new RolePaths(
                            ROLE_FRONTEND,
                            true,
                            List.of(FRONTEND_READ, FRONTEND_PUBLIC),
                            List.of(FRONTEND_READ, FRONTEND_PUBLIC)),
                    ROLE_DBA,
                    new RolePaths(
                            ROLE_DBA,
                            true,
                            List.of("**"),
                            List.of("**/db/migration/**", "scripts/sql/**")),
                    ROLE_DEVOPS,
                    new RolePaths(
                            ROLE_DEVOPS,
                            true,
                            List.of("**"),
                            List.of("docker/**", "deploy/**", "scripts/**", ".github/workflows/**")),
                    ROLE_QA,
                    new RolePaths(ROLE_QA, true, List.of("**"), List.of()),
                    ROLE_REVIEWER,
                    new RolePaths(ROLE_REVIEWER, true, List.of("**"), List.of()));

    /** 当前生效的 profile；只允许通过 {@link #loadProfile(Path)} 整体替换。 */
    private static volatile Map<String, RolePaths> activeRoles = DEFAULT_ROLES;

    /** 受限 runner 的校验命令（P1-3）；空表示该角色不做构建校验。 */
    private static volatile Map<String, List<DevVerifyCommand>> activeVerify = Map.of();

    private static final int MAX_PATH_LENGTH = 512;

    private static final List<String> BLACKLIST_DIRS = List.of(".git", ".ssh", "data");
    private static final List<String> SECRET_SUFFIXES =
            List.of(".pem", ".key", ".p12", ".jks", ".keystore", ".pfx", ".gpg", ".kdbx", ".ovpn");
    private static final List<String> SECRET_NAMES =
            List.of(
                    "id_rsa",
                    "id_ed25519",
                    "id_dsa",
                    ".npmrc",
                    ".netrc",
                    ".pgpass",
                    ".htpasswd");

    private DevPathPolicy() {}

    /**
     * 加载仓库路径 profile：只覆盖 {@code roles} 中出现的角色，其余角色保留默认值。
     *
     * <pre>
     * {"roles": {
     *   "project-implementer": {"read": ["**"], "write": ["backend/**", "agent/**"]},
     *   "project-frontend":    {"read": ["web/src/**"], "write": ["web/src/**"]}
     * }}
     * </pre>
     *
     * <p>空 {@code read} 表示关闭该角色的文件系统访问；缺省 {@code write} 表示只读。
     * 未知角色、非法 glob 与重复/空 profile 直接拒绝，避免静默沿用错误的仓库范围。
     *
     * <p>可选的 {@code verify} 段声明受限 runner 的构建/测试命令：
     * <pre>
     * "verify": {"project-implementer": [
     *   {"name": "backend-build", "command": ["mvn", "-o", "-q", "compile"], "timeoutSeconds": 900}
     * ]}
     * </pre>
     * 命令只接受 allowlist 内的二进制并以 argv 执行，不经过 shell。
     */
    public static synchronized void loadProfile(Path file) {
        if (file == null) {
            activeRoles = DEFAULT_ROLES;
            activeVerify = Map.of();
            return;
        }
        if (!Files.isRegularFile(file)) {
            throw new PayrollException("研发路径 profile 不存在：" + file);
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PayrollException("读取研发路径 profile 失败：" + e.getMessage());
        }
        JsonNode roles = root == null ? null : root.get("roles");
        if (roles == null || !roles.isObject() || roles.isEmpty()) {
            throw new PayrollException("研发路径 profile 缺少非空 roles 对象：" + file);
        }
        Map<String, RolePaths> loaded = new LinkedHashMap<>(DEFAULT_ROLES);
        roles.fields()
                .forEachRemaining(
                        entry -> {
                            String role = entry.getKey().trim().toLowerCase(Locale.ROOT);
                            RolePaths base = DEFAULT_ROLES.get(role);
                            if (base == null) {
                                throw new PayrollException(
                                        "研发路径 profile 含未知角色：" + entry.getKey());
                            }
                            JsonNode spec = entry.getValue();
                            if (spec == null || !spec.isObject()) {
                                throw new PayrollException(
                                        "研发路径 profile 角色配置必须是对象：" + role);
                            }
                            List<String> read = globs(role, spec, "read", true);
                            List<String> write = globs(role, spec, "write", false);
                            loaded.put(
                                    role, new RolePaths(role, !read.isEmpty(), read, write));
                        });
        Map<String, List<DevVerifyCommand>> verify = new LinkedHashMap<>();
        JsonNode verifyNode = root.get("verify");
        if (verifyNode != null && !verifyNode.isNull()) {
            if (!verifyNode.isObject()) {
                throw new PayrollException("研发路径 profile 的 verify 必须是对象：" + file);
            }
            verifyNode
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                String role = entry.getKey().trim().toLowerCase(Locale.ROOT);
                                if (DEFAULT_ROLES.get(role) == null) {
                                    throw new PayrollException(
                                            "研发路径 profile 的 verify 含未知角色：" + entry.getKey());
                                }
                                verify.put(role, verifyCommands(role, entry.getValue()));
                            });
        }
        activeRoles = Map.copyOf(loaded);
        activeVerify = Map.copyOf(verify);
    }

    /** 恢复内置 payroll profile；供配置回退与测试隔离使用。 */
    public static synchronized void resetProfile() {
        activeRoles = DEFAULT_ROLES;
        activeVerify = Map.of();
    }

    /** 当前 profile 下该角色的构建校验命令；未声明返回空列表。 */
    public static List<DevVerifyCommand> verifyCommands(String role) {
        if (role == null || role.isBlank()) {
            return List.of();
        }
        return activeVerify.getOrDefault(role.trim().toLowerCase(Locale.ROOT), List.of());
    }

    private static List<DevVerifyCommand> verifyCommands(String role, JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new PayrollException("研发路径 profile 的 verify." + role + " 必须是数组");
        }
        List<DevVerifyCommand> commands = new ArrayList<>();
        for (JsonNode item : node) {
            commands.add(verifyCommand(role, item));
        }
        return List.copyOf(commands);
    }

    private static DevVerifyCommand verifyCommand(String role, JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new PayrollException("研发路径 profile 的 verify." + role + " 项必须是对象");
        }
        JsonNode commandNode = item.get("command");
        if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
            throw new PayrollException(
                    "研发路径 profile 的 verify." + role + " 项缺少 command 数组");
        }
        List<String> argv = new ArrayList<>();
        for (JsonNode arg : commandNode) {
            if (!arg.isTextual()) {
                throw new PayrollException(
                        "研发路径 profile 的 verify." + role + " 的 command 只能包含字符串");
            }
            argv.add(arg.asText());
        }
        JsonNode nameNode = item.get("name");
        if (nameNode != null && !nameNode.isTextual()) {
            throw new PayrollException("研发路径 profile 的 verify." + role + " 的 name 必须是字符串");
        }
        String name = nameNode == null ? role : nameNode.asText();
        JsonNode timeoutNode = item.get("timeoutSeconds");
        if (timeoutNode != null && !timeoutNode.isInt()) {
            throw new PayrollException(
                    "研发路径 profile 的 verify." + role + " 的 timeoutSeconds 必须是整数");
        }
        int timeout = timeoutNode == null ? DevVerifyCommand.DEFAULT_TIMEOUT_SECONDS : timeoutNode.asInt();
        JsonNode imageNode = item.get("image");
        if (imageNode != null && !imageNode.isTextual()) {
            throw new PayrollException("研发路径 profile 的 verify." + role + " 的 image 必须是字符串");
        }
        String image = imageNode == null ? "" : imageNode.asText();
        return new DevVerifyCommand(name, argv, timeout, image);
    }

    private static List<String> globs(
            String role, JsonNode spec, String field, boolean required) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new PayrollException(
                        "研发路径 profile 缺少 " + role + "." + field + " 数组");
            }
            return List.of();
        }
        if (!node.isArray()) {
            throw new PayrollException(
                    "研发路径 profile 的 " + role + "." + field + " 必须是数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new PayrollException(
                        "研发路径 profile 的 " + role + "." + field + " 只能包含字符串");
            }
            values.add(validateGlob(role, field, item.asText()));
        }
        return List.copyOf(values);
    }

    private static String validateGlob(String role, String field, String glob) {
        String value = glob == null ? "" : glob.trim().replace('\\', '/');
        if (value.isEmpty()
                || value.length() > MAX_GLOB_LENGTH
                || value.startsWith("/")
                || value.startsWith("~")
                || value.indexOf(':') >= 0) {
            throw new PayrollException(
                    "研发路径 profile 的 " + role + "." + field + " 含非法 glob：" + glob);
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                throw new PayrollException(
                        "研发路径 profile 的 " + role + "." + field + " 不允许 ..：" + glob);
            }
        }
        return value;
    }

    /** 单个角色的读写范围；只读角色写白名单为空。 */
    public record RolePaths(
            String role, boolean readEnabled, List<String> readGlobs, List<String> writeGlobs) {

        public RolePaths {
            readGlobs = readGlobs == null ? List.of() : List.copyOf(readGlobs);
            writeGlobs = writeGlobs == null ? List.of() : List.copyOf(writeGlobs);
        }

        public boolean writable() {
            return !writeGlobs.isEmpty();
        }
    }

    /** 评估结果；拒绝时 {@code reason} 可直接进审计与任务失败原因。 */
    public record Outcome(boolean allowed, String path, String reason) {

        static Outcome allow(String path) {
            return new Outcome(true, path, "");
        }

        static Outcome deny(String path, String reason) {
            return new Outcome(false, path, reason);
        }
    }

    public static Optional<RolePaths> role(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeRoles.get(role.trim().toLowerCase(Locale.ROOT)));
    }

    public static boolean canRead(String role, String path) {
        return evaluate(role, path, false).allowed();
    }

    /** 供提示词与启动日志展示的权限摘要。 */
    public static String describeScope(String role) {
        Optional<RolePaths> resolved = role(role);
        if (resolved.isEmpty()) {
            return "未定义";
        }
        RolePaths paths = resolved.get();
        if (!paths.readEnabled()) {
            return "不授予文件系统访问";
        }
        String read =
                paths.readGlobs().contains("**")
                        ? "仓库内（黑名单除外）"
                        : String.join("、", paths.readGlobs());
        String write =
                paths.writeGlobs().isEmpty() ? "无写入" : String.join("、", paths.writeGlobs());
        return "只读 " + read + "；写入 " + write;
    }

    public static boolean canWrite(String role, String path) {
        return evaluate(role, path, true).allowed();
    }

    /**
     * 目录是否可列出：命中读白名单，或是只读白名单路径的上层目录。
     * 只用于 ls 导航，不代表可读其中的文件内容。
     */
    public static boolean canList(String role, String path) {
        Optional<RolePaths> resolved = role(role);
        if (resolved.isEmpty() || !resolved.get().readEnabled()) {
            return false;
        }
        Optional<String> normalized = normalize(path);
        if (normalized.isEmpty() || blacklisted(normalized.get())) {
            return false;
        }
        String dir = normalized.get();
        for (String glob : resolved.get().readGlobs()) {
            if (matches(List.of(glob), dir)) {
                return true;
            }
            String prefix = literalPrefix(glob);
            if (prefix.isEmpty() || dir.equals(prefix) || prefix.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 读写统一入口；{@code write=true} 同时要求角色具备写白名单。 */
    public static Outcome evaluate(String role, String path, boolean write) {
        Optional<RolePaths> resolved = role(role);
        if (resolved.isEmpty()) {
            return Outcome.deny(path, "未知角色：" + role);
        }
        RolePaths paths = resolved.get();
        if (!paths.readEnabled()) {
            return Outcome.deny(path, paths.role() + " 不授予文件系统访问");
        }
        Optional<String> normalized = normalize(path);
        if (normalized.isEmpty()) {
            return Outcome.deny(path, "非法路径：只接受仓库内相对路径");
        }
        String relative = normalized.get();
        if (blacklisted(relative)) {
            return Outcome.deny(relative, "命中路径黑名单：" + relative);
        }
        if (!write) {
            return matches(paths.readGlobs(), relative)
                    ? Outcome.allow(relative)
                    : Outcome.deny(relative, paths.role() + " 只能读取白名单路径");
        }
        if (!paths.writable()) {
            return Outcome.deny(relative, paths.role() + " 为只读角色，禁止写入");
        }
        return matches(paths.writeGlobs(), relative)
                ? Outcome.allow(relative)
                : Outcome.deny(relative, "越权写入：" + relative + " 不在 " + paths.role() + " 白名单内");
    }

    /**
     * 归一化为仓库内相对路径：反斜杠转正斜杠、去掉 {@code ./} 前缀、折叠重复斜杠。
     * 绝对路径、盘符/冒号（含 NTFS 数据流）、控制字符、超长路径、{@code ..} 段与空路径
     * 均返回 {@link Optional#empty()}。
     */
    static Optional<String> normalize(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String value = path.trim().replace('\\', '/');
        if (value.isEmpty()
                || value.length() > MAX_PATH_LENGTH
                || value.startsWith("/")
                || value.startsWith("~")
                || value.indexOf(':') >= 0) {
            return Optional.empty();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return Optional.empty();
            }
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return Optional.empty();
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized.toString());
    }

    static boolean blacklisted(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        for (String segment : lower.split("/")) {
            if (BLACKLIST_DIRS.contains(segment)) {
                return true;
            }
        }
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (".env".equals(fileName) || fileName.startsWith(".env.")) {
            return true;
        }
        for (String suffix : SECRET_SUFFIXES) {
            if (fileName.endsWith(suffix)) {
                return true;
            }
        }
        for (String name : SECRET_NAMES) {
            if (fileName.startsWith(name)) {
                return true;
            }
        }
        if ("settings.xml".equals(fileName)
                && (lower.startsWith(".m2/") || lower.contains("/.m2/"))) {
            return true;
        }
        return fileName.startsWith("credentials") || fileName.startsWith("secrets");
    }

    private static boolean matches(List<String> globs, String relative) {
        for (String glob : globs) {
            if (Pattern.matches(toRegex(glob), relative)) {
                return true;
            }
        }
        return false;
    }

    /** glob 中第一个通配段之前的字面前缀；以 {@code **} 开头时为空串。 */
    private static String literalPrefix(String glob) {
        StringBuilder prefix = new StringBuilder();
        for (String segment : glob.split("/")) {
            if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) {
                break;
            }
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            prefix.append(segment);
        }
        return prefix.toString();
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        String[] segments = glob.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if ("**".equals(segment)) {
                regex.append(last ? ".*" : "(?:[^/]+/)*");
                continue;
            }
            if ("*".equals(segment)) {
                regex.append("[^/]*");
            } else {
                for (char c : segment.toCharArray()) {
                    switch (c) {
                        case '*' -> regex.append("[^/]*");
                        case '?' -> regex.append("[^/]");
                        case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> regex.append('\\').append(c);
                        default -> regex.append(c);
                    }
                }
            }
            if (!last) {
                regex.append('/');
            }
        }
        return regex.toString();
    }
}
