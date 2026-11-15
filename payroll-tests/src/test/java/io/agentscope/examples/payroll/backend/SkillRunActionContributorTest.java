package io.agentscope.examples.payroll.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.agent.SkillRunTools;
import io.agentscope.examples.payroll.service.DefaultActionExecutor;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillRunActionContributorTest {

    private final List<SandboxGateway.Script> sent = new ArrayList<>();
    private final AtomicReference<Integer> sentTimeout = new AtomicReference<>();

    private SandboxGateway gateway(SandboxGateway.Execution execution) {
        return new SandboxGateway() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Execution execute(
                    long userId,
                    String skillId,
                    String scriptPath,
                    List<String> args,
                    int timeoutMs,
                    List<Script> scripts) {
                sent.clear();
                sent.addAll(scripts);
                sentTimeout.set(timeoutMs);
                assertEquals(7L, userId);
                assertEquals("payroll-auditor", skillId);
                assertEquals("scripts/check.py", scriptPath);
                assertEquals(List.of("--month", "2026-11"), args);
                return execution;
            }
        };
    }

    @Test
    @DisplayName("只注册 skill_run 并沿用对话权限")
    void exposesSkillRunAction() {
        SkillRunActionContributor contributor = contributor(settings(true), success(), auditor(true));

        assertEquals(
                Map.of(SkillRunTools.SKILL_RUN, Permissions.CHAT_USE), contributor.toolPermissions());
        assertTrue(contributor.localActions().containsKey(SkillRunTools.SKILL_RUN));
    }

    @Test
    @DisplayName("执行开关关闭时拒绝执行")
    void rejectsWhenDisabled() {
        SkillRunActionContributor contributor =
                contributor(settings(false), success(), auditor(true));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(contributor));

        assertTrue(error.getMessage().contains("未启用"), error.getMessage());
    }

    @Test
    @DisplayName("技能缺失、关闭执行或脚本不存在都拒绝执行")
    void rejectsInvalidTargets() {
        SkillRunActionContributor missingSkill =
                new SkillRunActionContributor(settings(true), gateway(success()), skillId -> null);
        SkillRunActionContributor disabledSkill =
                contributor(settings(true), success(), auditor(false));
        SkillRunActionContributor missingScript =
                contributor(
                        settings(true),
                        success(),
                        skill("payroll-auditor", true, Map.of("scripts/other.py", "x")));

        assertTrue(
                assertThrows(IllegalStateException.class, () -> run(missingSkill))
                        .getMessage()
                        .contains("技能不存在"));
        assertTrue(
                assertThrows(IllegalStateException.class, () -> run(disabledSkill))
                        .getMessage()
                        .contains("关闭脚本执行"));
        assertTrue(
                assertThrows(IllegalStateException.class, () -> run(missingScript))
                        .getMessage()
                        .contains("不存在脚本"));
        assertTrue(sent.isEmpty(), "拒绝分支不应触达沙箱");
    }

    @Test
    @DisplayName("成功执行只回传预览与哈希，脚本包仅含 scripts/ 并排序")
    void returnsPreviewAndScripts() {
        SkillRunActionContributor contributor =
                contributor(
                        settings(true),
                        new SandboxGateway.Execution(
                                SandboxGateway.Execution.OK,
                                0,
                                "x".repeat(1_200),
                                "warn",
                                42,
                                "abc123",
                                true,
                                null),
                        auditor(true));

        Object result = run(contributor);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        assertEquals("OK", payload.get("status"));
        assertEquals("abc123", payload.get("script_sha256"));
        assertEquals(true, payload.get("truncated"));
        String preview = (String) payload.get("stdout_preview");
        assertEquals(1_001, preview.length());
        assertTrue(preview.endsWith("…"));
        assertEquals(Integer.valueOf(5_000), sentTimeout.get());
        assertEquals(
                List.of("scripts/a.py", "scripts/check.py"),
                sent.stream().map(SandboxGateway.Script::path).toList());
    }

    @Test
    @DisplayName("非零退出与超时都转成可读错误")
    void reportsFailures() {
        SkillRunActionContributor failedExit =
                contributor(
                        settings(true),
                        new SandboxGateway.Execution(
                                SandboxGateway.Execution.ERROR,
                                3,
                                "",
                                "boom",
                                10,
                                "sha",
                                false,
                                "脚本异常"),
                        auditor(true));
        SkillRunActionContributor timeout =
                contributor(
                        settings(true),
                        new SandboxGateway.Execution(
                                SandboxGateway.Execution.TIMEOUT,
                                -1,
                                "",
                                "",
                                10_000,
                                "sha",
                                false,
                                "已终止"),
                        auditor(true));

        String exitMessage =
                assertThrows(IllegalStateException.class, () -> run(failedExit)).getMessage();
        String timeoutMessage =
                assertThrows(IllegalStateException.class, () -> run(timeout)).getMessage();

        assertTrue(exitMessage.contains("退出码 3"), exitMessage);
        assertTrue(exitMessage.contains("boom"), exitMessage);
        assertTrue(timeoutMessage.contains("执行超时"), timeoutMessage);
    }

    private static SandboxGateway.Execution success() {
        return new SandboxGateway.Execution(
                SandboxGateway.Execution.OK, 0, "ok", "", 5, "sha", false, null);
    }

    private SkillRunActionContributor contributor(
            SkillExecutionSettings settings, SandboxGateway.Execution execution, AgentSkill skill) {
        return new SkillRunActionContributor(
                settings, gateway(execution), skillId -> skill);
    }

    private static Object run(SkillRunActionContributor contributor) {
        DefaultActionExecutor.LocalAction action =
                contributor.localActions().get(SkillRunTools.SKILL_RUN);
        Map<String, Object> params =
                Map.of(
                        "skill_id", "payroll-auditor",
                        "script_path", "scripts/check.py",
                        "args", List.of("--month", "2026-11"));
        return action.execute(params, 7L);
    }

    private static SkillExecutionSettings settings(boolean enabled) {
        return new SkillExecutionSettings(enabled, "http://127.0.0.1:8098", "token", 5_000);
    }

    private static AgentSkill auditor(boolean executionEnabled) {
        return skill(
                "payroll-auditor",
                executionEnabled,
                Map.of(
                        "scripts/check.py", "print('ok')",
                        "references/faq.md", "# FAQ",
                        "scripts/a.py", "print('a')"));
    }

    private static AgentSkill skill(
            String name, boolean executionEnabled, Map<String, String> resources) {
        AgentSkill.Builder builder =
                AgentSkill.builder()
                        .name(name)
                        .description(name)
                        .skillContent("## 角色\n测试角色。")
                        .source("nacos");
        resources.forEach(builder::addResource);
        if (executionEnabled) {
            builder.putMetadata("execution", Map.of("enabled", true));
        }
        return builder.build();
    }
}
