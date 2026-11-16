package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.SkillExecutions;
import io.agentscope.examples.payroll.skill.SkillInitRequirements;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 脚本执行工具：只提交执行提案，永不直接运行脚本。
 *
 * <p>注册条件（三者同时满足）：沙箱已配置、技能 frontmatter {@code execution.enabled=true}、
 * 技能包内至少一个 {@code scripts/*}。执行本身由用户在确认卡片提交后，由 Web 侧按提案参数
 * 重建脚本包并调用沙箱服务；未确认时工具只返回 PENDING_CONFIRM。
 */
public final class SkillRunTools {

    public static final String SKILL_RUN = "skill_run";

    private static final Logger log = LoggerFactory.getLogger(SkillRunTools.class);
    private static final String SCRIPTS_PREFIX = "scripts/";

    private final List<AgentSkill> skills;
    private final SandboxGateway gateway;
    private final int timeoutMs;

    public SkillRunTools(List<AgentSkill> skills, SandboxGateway gateway, int timeoutMs) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
        this.gateway = gateway;
        this.timeoutMs = timeoutMs;
    }

    /** 至少一个技能开启执行且含脚本，才值得注册工具。 */
    public static boolean hasExecutableScripts(List<AgentSkill> skills) {
        if (skills == null) {
            return false;
        }
        return skills.stream()
                .anyMatch(
                        skill ->
                                SkillExecutions.executionEnabled(skill)
                                        && skill.getResourcePaths().stream()
                                                .anyMatch(path -> path.startsWith(SCRIPTS_PREFIX)));
    }

    /** 注册条件：沙箱可用 + 存在可执行脚本。 */
    public static boolean available(List<AgentSkill> skills, SandboxGateway gateway) {
        return gateway != null && gateway.enabled() && hasExecutableScripts(skills);
    }

    public static String guidance(List<AgentSkill> skills, int timeoutMs) {
        if (!hasExecutableScripts(skills)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (AgentSkill skill : skills) {
            if (!SkillExecutions.executionEnabled(skill)) {
                continue;
            }
            List<String> scripts =
                    skill.getResourcePaths().stream()
                            .filter(path -> path.startsWith(SCRIPTS_PREFIX))
                            .sorted()
                            .toList();
            if (!scripts.isEmpty()) {
                lines.add(skill.getName() + "：" + String.join("、", scripts));
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return """
                ## 技能脚本执行
                可执行脚本：%s。
                需要时调用 skill_run(script_path, args) 提交执行；执行前必须由用户在确认卡片中确认，未确认不会运行。
                脚本在禁网、只读、非 root 的一次性容器中运行，最长 %d 秒，不能安装依赖或访问宿主文件。
                初始化后的技能数据只读挂载在容器内 /data，脚本可直接读取；技能声明了 setup 时，数据未初始化会被拒绝。
                多个技能含同名脚本时用 skill_id 指定；输出超长会截断。
                """
                .formatted(String.join("；", lines), Math.max(1, timeoutMs / 1000));
    }

    @Tool(
            name = SKILL_RUN,
            description =
                    "提交本技能包 scripts/ 下的脚本执行。执行前需要用户在确认卡片确认；"
                            + "沙箱禁网、只读、最长 10 秒，返回退出码与截断后的输出。",
            readOnly = false)
    public String run(
            @ToolParam(name = "script_path", description = "脚本相对路径，如 scripts/check.py")
                    String scriptPath,
            @ToolParam(
                            name = "args",
                            description = "命令行参数，按数组逐个传递，如 [\"--month\", \"2026-11\"]",
                            required = false)
                    List<String> args,
            @ToolParam(
                            name = "skill_id",
                            description = "可选，绑定技能 ID；多个技能含同名脚本时必填",
                            required = false)
                    String skillId,
            RuntimeContext context) {
        long startedAt = System.nanoTime();
        Map<String, Object> arguments = new LinkedHashMap<>();
        String result;
        String normalized;
        List<String> cleanArgs;
        try {
            normalized = SandboxProtocol.requireScriptPath(scriptPath);
            cleanArgs = SandboxProtocol.requireArgs(args);
        } catch (IllegalArgumentException e) {
            result = error(e.getMessage());
            record(context, arguments, result, startedAt);
            return result;
        }
        arguments.put("script_path", normalized);
        if (!cleanArgs.isEmpty()) {
            arguments.put("args", cleanArgs);
        }
        AgentSkill target;
        try {
            target = resolve(normalized, skillId);
        } catch (PayloadException e) {
            result = error(e.getMessage());
            record(context, arguments, result, startedAt);
            return result;
        }
        if (target == null) {
            result = error("本智能体绑定的技能中没有该脚本：" + normalized);
            record(context, arguments, result, startedAt);
            return result;
        }
        arguments.put("skill_id", target.getName());
        if (!SkillExecutions.executionEnabled(target)) {
            result = error("技能 " + target.getName() + " 未开启脚本执行");
            record(context, arguments, result, startedAt);
            return result;
        }
        if (SkillInitRequirements.requiresData(target)) {
            String notReady = dataNotReady(target);
            if (notReady != null) {
                result = error(notReady);
                record(context, arguments, result, startedAt);
                return result;
            }
        }
        ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
        if (guard == null) {
            result = error("脚本执行需要会话内确认，当前调用上下文不可用，已拒绝直接运行");
            record(context, arguments, result, startedAt);
            return result;
        }
        String pending = guard.intercept(SKILL_RUN, arguments, false, false, true);
        result =
                pending != null
                        ? pending
                        : error("脚本未提交执行：缺少用户确认");
        log.info(
                "[技能脚本] 生成执行提案：skill={}，script={}，args={}，timeoutMs={}",
                target.getName(),
                normalized,
                cleanArgs.size(),
                timeoutMs);
        record(context, arguments, result, startedAt);
        return result;
    }

    /**
     * 技能声明 setup 时先核对数据状态；就绪返回 null，否则返回可直接展示的提示。
     */
    private String dataNotReady(AgentSkill skill) {
        if (gateway == null) {
            return "技能需要初始化数据，但当前无法访问沙箱，已拒绝执行";
        }
        SandboxGateway.SkillData data;
        try {
            data = gateway.skillData(skill.getName());
        } catch (RuntimeException e) {
            return "无法确认技能数据状态：" + e.getMessage();
        }
        String status = data == null ? null : data.status();
        if (status == null) {
            return "技能数据未初始化，请先在技能管理台执行初始化";
        }
        return switch (status) {
            case SandboxGateway.SkillData.READY -> null;
            case SandboxGateway.SkillData.INITIALIZING -> "技能数据正在初始化，请稍后重试";
            case SandboxGateway.SkillData.FAILED -> "技能数据初始化失败，请先在技能管理台重新初始化"
                    + (data.message() == null || data.message().isBlank()
                            ? ""
                            : "：" + data.message());
            case SandboxGateway.SkillData.BUSY,
                    SandboxGateway.SkillData.UNAVAILABLE,
                    SandboxGateway.SkillData.INVALID ->
                    "无法确认技能数据状态："
                            + (data.message() == null ? status : data.message());
            default -> "技能数据未初始化，请先在技能管理台执行初始化";
        };
    }

    /**
     * 未指定技能时只有唯一匹配才选中；多个技能含同名脚本必须显式传 skill_id，
     * 避免执行属于其他角色的脚本。
     */
    private AgentSkill resolve(String path, String skillId) {
        String requested = skillId == null ? "" : skillId.trim();
        if (!requested.isEmpty()) {
            for (AgentSkill skill : skills) {
                if (skill != null
                        && (requested.equals(skill.getName())
                                || requested.equals(skill.getSkillId()))) {
                    return skill.getResource(path) == null ? null : skill;
                }
            }
            throw new PayloadException("本智能体未绑定技能：" + requested);
        }
        Set<String> ids = new LinkedHashSet<>();
        AgentSkill matched = null;
        for (AgentSkill skill : skills) {
            if (skill != null && skill.getResource(path) != null) {
                ids.add(skill.getName());
                matched = skill;
            }
        }
        if (ids.size() > 1) {
            throw new PayloadException("多个技能包含该脚本，请传入 skill_id：" + String.join("、", ids));
        }
        return matched;
    }

    private void record(
            RuntimeContext context, Map<String, Object> arguments, String result, long startedAt) {
        ToolTraceRecorder recorder = context == null ? null : context.get(ToolTraceRecorder.class);
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(
                    SKILL_RUN,
                    JsonSupport.toJson(arguments),
                    result,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("[技能脚本] 记录工具追溯失败：{}", e.getMessage());
        }
    }

    private static String error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("message", message);
        return JsonSupport.toJson(response);
    }

    /** 内部错误类型，避免用异常消息做分支判断。 */
    private static final class PayloadException extends RuntimeException {
        private PayloadException(String message) {
            super(message);
        }
    }
}
