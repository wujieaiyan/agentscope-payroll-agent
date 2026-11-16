package io.agentscope.examples.payroll.backend;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.agent.SkillRunTools;
import io.agentscope.examples.payroll.service.ActionContributor;
import io.agentscope.examples.payroll.service.DefaultActionExecutor;
import io.agentscope.examples.payroll.service.SandboxClient;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.skill.SkillExecutions;
import io.agentscope.examples.payroll.skill.SkillInitRequirements;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 把 {@code skill_run} 接入确认执行链：用户在卡片确认后，由 Web 节点按提案参数
 * 重新读取当前技能包并调用沙箱，不信任提案里可能已过期的脚本内容。
 *
 * <p>参数只包含技能 ID、脚本路径与参数数组；脚本正文与哈希在执行时重新计算，
 * 审计因此不会落库整段脚本或完整输出。
 */
@Component
public class SkillRunActionContributor implements ActionContributor {

    private static final String SCRIPTS_PREFIX = "scripts/";
    private static final int PREVIEW_CHARS = 1_000;

    private final SkillExecutionSettings settings;
    private final SandboxGateway gateway;
    private final Function<String, AgentSkill> skillLookup;

    public SkillRunActionContributor() {
        this(
                SkillExecutionSettings.fromEnvironment(),
                null,
                skillId -> SkillRepositories.shared().getSkill(skillId));
    }

    SkillRunActionContributor(
            SkillExecutionSettings settings,
            SandboxGateway gateway,
            Function<String, AgentSkill> skillLookup) {
        this.settings = settings;
        this.gateway = gateway == null ? new SandboxClient(settings) : gateway;
        this.skillLookup = skillLookup;
    }

    @Override
    public Map<String, DefaultActionExecutor.LocalAction> localActions() {
        return Map.of(SkillRunTools.SKILL_RUN, this::execute);
    }

    @Override
    public Map<String, String> toolPermissions() {
        return Map.of(SkillRunTools.SKILL_RUN, Permissions.CHAT_USE);
    }

    private Object execute(Map<String, Object> params, long userId) {
        if (!settings.configured()) {
            throw new IllegalStateException(
                    "技能脚本执行未启用（PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN）");
        }
        String skillId = text(params.get("skill_id"));
        String scriptPath = text(params.get("script_path"));
        if (skillId.isBlank() || scriptPath.isBlank()) {
            throw new IllegalStateException("执行提案缺少技能或脚本路径，已拒绝执行");
        }
        AgentSkill skill = skillLookup.apply(skillId);
        if (skill == null) {
            throw new IllegalStateException("技能不存在或未加载：" + skillId);
        }
        if (!SkillExecutions.executionEnabled(skill)) {
            throw new IllegalStateException("技能已关闭脚本执行：" + skillId);
        }
        String content = skill.getResource(scriptPath);
        if (content == null) {
            throw new IllegalStateException("技能包中不存在脚本：" + scriptPath);
        }
        List<SandboxGateway.Script> scripts =
                skill.getResourcePaths().stream()
                        .filter(path -> path.startsWith(SCRIPTS_PREFIX))
                        .sorted()
                        .map(path -> new SandboxGateway.Script(path, skill.getResource(path)))
                        .toList();
        SandboxGateway.Execution execution =
                gateway.execute(
                        userId,
                        skillId,
                        scriptPath,
                        strings(params.get("args")),
                        settings.timeoutMs(),
                        scripts,
                        SkillInitRequirements.requiresData(skill));
        if (!execution.succeeded()) {
            throw new IllegalStateException(failureMessage(execution));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("skill_id", skillId);
        result.put("script_path", scriptPath);
        result.put("exit_code", execution.exitCode());
        result.put("duration_ms", execution.durationMs());
        result.put("script_sha256", execution.scriptSha256());
        result.put("truncated", execution.truncated());
        result.put("stdout_preview", preview(execution.stdout()));
        result.put("stderr_preview", preview(execution.stderr()));
        return result;
    }

    private static String failureMessage(SandboxGateway.Execution execution) {
        String detail =
                execution.message() == null || execution.message().isBlank()
                        ? "脚本执行失败"
                        : execution.message();
        return switch (execution.status()) {
            case SandboxGateway.Execution.TIMEOUT -> "执行超时：" + detail;
            case SandboxGateway.Execution.BUSY -> "沙箱繁忙：" + detail;
            case SandboxGateway.Execution.UNAVAILABLE -> "沙箱不可用：" + detail;
            case SandboxGateway.Execution.INVALID -> "沙箱拒绝请求：" + detail;
            case SandboxGateway.Execution.DATA_NOT_READY -> "技能数据未就绪：" + detail;
            default ->
                    execution.exitCode() == 0
                            ? detail
                            : "脚本退出码 " + execution.exitCode() + "：" + detail
                                    + (execution.stderr().isBlank()
                                            ? ""
                                            : "；" + preview(execution.stderr()));
        };
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= PREVIEW_CHARS
                ? text
                : text.substring(0, PREVIEW_CHARS) + "…";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> result.add(item == null ? "" : String.valueOf(item)));
        return result;
    }
}
