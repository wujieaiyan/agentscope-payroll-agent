package io.agentscope.examples.payroll.devteam;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 研发域工具 allow/deny 策略（P1-4）：默认拒绝，只有显式列入白名单的工具才会注册给模型。
 *
 * <p>文件工具按角色权限与是否绑定编辑会话决定；{@code skill_read}/{@code kb_search} 是只读
 * 共享能力，所有已知研发角色可用。shell、终端、进程、容器、网络、MCP 与 Git 写操作一律拒绝，
 * 未知工具按「可能修改」处理并拒绝（fail-closed），不信任工具自报只读。
 */
public final class DevToolPolicy {

    public static final Set<String> READ_TOOLS =
            Set.of("read_file", "grep_files", "glob_files", "list_files");

    public static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");

    public static final Set<String> SHARED_READ_TOOLS = Set.of("skill_read", "kb_search");

    /** 命中任一关键字即拒绝；用于拦截 shell/终端/网络/容器/Git 写等能力。 */
    private static final List<String> DANGEROUS_TOKENS =
            List.of(
                    "shell",
                    "terminal",
                    "exec",
                    "process",
                    "bash",
                    "powershell",
                    "docker",
                    "http",
                    "web_",
                    "browser",
                    "network",
                    "curl",
                    "wget",
                    "git_",
                    "push",
                    "deploy",
                    "publish");

    private DevToolPolicy() {}

    /**
     * @param allowed 是否允许注册给模型
     * @param reason 拒绝原因；允许时为空串
     */
    public record Decision(boolean allowed, String reason) {

        static Decision allow() {
            return new Decision(true, "");
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 单个工具判定；{@code writableSession} 表示已绑定任务编辑会话。 */
    public static Decision evaluate(String role, String toolName, boolean writableSession) {
        Optional<DevPathPolicy.RolePaths> paths = DevPathPolicy.role(role);
        if (paths.isEmpty()) {
            return Decision.deny("未知研发角色：" + role);
        }
        String name = normalize(toolName);
        if (name.isEmpty()) {
            return Decision.deny("工具名为空");
        }
        if (READ_TOOLS.contains(name)) {
            return paths.get().readEnabled()
                    ? Decision.allow()
                    : Decision.deny(paths.get().role() + " 不授予文件系统读取");
        }
        if (WRITE_TOOLS.contains(name)) {
            if (!paths.get().writable()) {
                return Decision.deny(paths.get().role() + " 为只读角色，禁止写入工具");
            }
            return writableSession
                    ? Decision.allow()
                    : Decision.deny("未绑定编辑会话，写工具不开放");
        }
        if (SHARED_READ_TOOLS.contains(name)) {
            return Decision.allow();
        }
        if (dangerous(name)) {
            return Decision.deny("研发域禁止危险工具：" + name);
        }
        return Decision.deny("研发域未授权工具：" + name);
    }

    public static boolean allowed(String role, String toolName, boolean writableSession) {
        return evaluate(role, toolName, writableSession).allowed();
    }

    /** 当前角色允许注册的工具集合；用于构建后裁剪 harness 追加的工具。 */
    public static Set<String> allowedTools(String role, boolean writableSession) {
        Set<String> allowed = new LinkedHashSet<>(SHARED_READ_TOOLS);
        Optional<DevPathPolicy.RolePaths> paths = DevPathPolicy.role(role);
        if (paths.isEmpty()) {
            return Set.of();
        }
        if (paths.get().readEnabled()) {
            allowed.addAll(READ_TOOLS);
        }
        if (paths.get().writable() && writableSession) {
            allowed.addAll(WRITE_TOOLS);
        }
        return Set.copyOf(allowed);
    }

    private static boolean dangerous(String name) {
        if (name.startsWith("mcp__")) {
            return true;
        }
        for (String token : DANGEROUS_TOKENS) {
            if (name.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }
}
