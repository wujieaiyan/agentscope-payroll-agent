package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillRunToolsTest {

    private final AtomicInteger executions = new AtomicInteger();

    private final SandboxGateway gateway =
            new SandboxGateway() {
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
                    executions.incrementAndGet();
                    throw new AssertionError("工具侧不应直接执行脚本");
                }

                @Override
                public SkillData initSkill(InitRequest request) {
                    throw new AssertionError("工具侧不应初始化技能");
                }

                @Override
                public SkillData skillData(String skillId) {
                    throw new AssertionError("工具侧不应查询技能数据");
                }

                @Override
                public SkillData deleteSkillData(String skillId) {
                    throw new AssertionError("工具侧不应删除技能数据");
                }
            };

    @Test
    @DisplayName("仅开启执行且有 scripts/ 文件时才注册并输出提示")
    void availabilityRequiresEnabledScripts() {
        AgentSkill runnable = skill("payroll-auditor", true, Map.of("scripts/check.py", "print(1)"));
        AgentSkill disabled = skill("payroll-auditor", false, Map.of("scripts/check.py", "print(1)"));
        AgentSkill referencesOnly =
                skill("payroll-auditor", true, Map.of("references/faq.md", "# FAQ"));

        assertTrue(SkillRunTools.hasExecutableScripts(List.of(runnable)));
        assertFalse(SkillRunTools.hasExecutableScripts(List.of(disabled)));
        assertFalse(SkillRunTools.hasExecutableScripts(List.of(referencesOnly)));
        assertFalse(SkillRunTools.hasExecutableScripts(null));

        assertTrue(SkillRunTools.available(List.of(runnable), gateway));
        assertFalse(SkillRunTools.available(List.of(runnable), null));
        assertFalse(
                SkillRunTools.available(
                        List.of(runnable),
                        new SandboxGateway() {
                            @Override
                            public boolean enabled() {
                                return false;
                            }

                            @Override
                            public Execution execute(
                                    long userId,
                                    String skillId,
                                    String scriptPath,
                                    List<String> args,
                                    int timeoutMs,
                                    List<Script> scripts) {
                                throw new AssertionError("未启用不应执行");
                            }

                            @Override
                            public SkillData initSkill(InitRequest request) {
                                throw new AssertionError("未启用不应初始化");
                            }

                            @Override
                            public SkillData skillData(String skillId) {
                                throw new AssertionError("未启用不应查询数据");
                            }

                            @Override
                            public SkillData deleteSkillData(String skillId) {
                                throw new AssertionError("未启用不应删除数据");
                            }
                        }));

        String guidance = SkillRunTools.guidance(List.of(runnable), 5000);
        assertTrue(guidance.contains("scripts/check.py"), guidance);
        assertTrue(guidance.contains("5 秒"), guidance);
        assertTrue(guidance.contains("确认卡片"), guidance);
        assertEquals("", SkillRunTools.guidance(List.of(disabled), 5000));
    }

    @Test
    @DisplayName("非法路径与未绑定脚本直接返回错误，不生成提案")
    void rejectsInvalidAndUnboundScripts() {
        SkillRunTools tools = tools(skill("payroll-auditor", true, Map.of("scripts/check.py", "x")));

        String badPath = tools.run("references/faq.md", null, null, context());
        String missing = tools.run("scripts/other.py", null, null, context());
        String unknownSkill =
                tools.run("scripts/check.py", null, "payroll-ghost", context());

        assertTrue(badPath.contains("\"ERROR\""), badPath);
        assertTrue(missing.contains("没有该脚本"), missing);
        assertTrue(unknownSkill.contains("未绑定技能"), unknownSkill);
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("同名脚本跨技能时必须显式指定 skill_id")
    void requiresSkillIdForAmbiguousScripts() {
        AgentSkill first = skill("payroll-auditor", true, Map.of("scripts/check.py", "x"));
        AgentSkill second = skill("payroll-calculator", true, Map.of("scripts/check.py", "x"));
        SkillRunTools tools = tools(first, second);

        String ambiguous = tools.run("scripts/check.py", null, null, context());
        String explicit = tools.run("scripts/check.py", null, "payroll-calculator", context());

        assertTrue(ambiguous.contains("多个技能"), ambiguous);
        assertTrue(explicit.contains("PENDING_CONFIRM"), explicit);
    }

    @Test
    @DisplayName("技能未开启执行时拒绝提交")
    void rejectsDisabledSkill() {
        SkillRunTools tools = tools(skill("payroll-auditor", false, Map.of("scripts/check.py", "x")));

        String result = tools.run("scripts/check.py", null, null, context());

        assertTrue(result.contains("未开启脚本执行"), result);
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("缺少会话上下文时拒绝执行")
    void rejectsWithoutGuard() {
        SkillRunTools tools = tools(skill("payroll-auditor", true, Map.of("scripts/check.py", "x")));

        String result = tools.run("scripts/check.py", null, null, RuntimeContext.builder().userId("7").build());

        assertTrue(result.contains("会话内确认"), result);
    }

    @Test
    @DisplayName("正常调用只生成 PENDING_CONFIRM 提案，参数保留规范化路径")
    void submitsProposalOnly() {
        AgentSkill auditor = skill("payroll-auditor", true, Map.of("scripts/check.py", "x"));
        SkillRunTools tools = tools(auditor);
        RuntimeContext context = context();

        String result = tools.run(" scripts/check.py ", List.of("--month", "2026-11"), null, context);

        Map<String, Object> payload =
                JsonSupport.fromJson(result, new TypeReference<Map<String, Object>>() {});
        assertEquals("PENDING_CONFIRM", payload.get("status"));
        ActionGuard guard = context.get(ActionGuard.class);
        assertNotNull(guard.firstProposal());
        assertEquals(SkillRunTools.SKILL_RUN, guard.firstProposal().toolName());
        Map<String, Object> params =
                JsonSupport.fromJson(
                        guard.firstProposal().paramsJson(),
                        new TypeReference<Map<String, Object>>() {});
        assertEquals("scripts/check.py", params.get("script_path"));
        assertEquals(List.of("--month", "2026-11"), params.get("args"));
        assertEquals("payroll-auditor", params.get("skill_id"));
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("声明 setup 的技能在数据未就绪时直接报错，READY 后才生成提案")
    void rejectsWhenSkillDataNotReady() {
        AgentSkill runnable =
                skill(
                        "payroll-auditor",
                        true,
                        Map.of("setup.sh", "sh setup.sh", "scripts/check.py", "x"));

        SkillRunTools missing =
                new SkillRunTools(List.of(runnable), dataGateway("UNKNOWN", null), 5000);
        String missingResult = missing.run("scripts/check.py", null, null, context());
        assertTrue(missingResult.contains("未初始化"), missingResult);
        assertTrue(missingResult.contains("技能管理台"), missingResult);

        SkillRunTools failed =
                new SkillRunTools(List.of(runnable), dataGateway("FAILED", "网络不可用"), 5000);
        String failedResult = failed.run("scripts/check.py", null, null, context());
        assertTrue(failedResult.contains("重新初始化"), failedResult);
        assertTrue(failedResult.contains("网络不可用"), failedResult);

        SkillRunTools ready =
                new SkillRunTools(List.of(runnable), dataGateway("READY", null), 5000);
        String readyResult = ready.run("scripts/check.py", null, null, context());
        assertTrue(readyResult.contains("PENDING_CONFIRM"), readyResult);
        assertEquals(0, executions.get());
    }

    private SkillRunTools tools(AgentSkill... skills) {
        return new SkillRunTools(List.of(skills), gateway, 5000);
    }

    private static SandboxGateway dataGateway(String status, String message) {
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
                throw new AssertionError("工具侧不应直接执行脚本");
            }

            @Override
            public SkillData initSkill(InitRequest request) {
                throw new AssertionError("工具侧不应初始化技能");
            }

            @Override
            public SkillData skillData(String skillId) {
                return new SkillData(status, skillId, null, 0, 0, 0, "", message);
            }

            @Override
            public SkillData deleteSkillData(String skillId) {
                throw new AssertionError("工具侧不应删除技能数据");
            }
        };
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .userId("7")
                .sessionId("s-1")
                .put(
                        ActionGuard.class,
                        new ActionGuard(
                                7L,
                                1001L,
                                "AGENT",
                                true,
                                15,
                                ToolSafety.defaults(),
                                ChatStreamListener.NOOP))
                .build();
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
