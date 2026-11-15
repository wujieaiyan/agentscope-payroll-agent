package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillReadToolsTest {

    @Test
    @DisplayName("读取已绑定技能的参考文件")
    void readsBoundResource() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(
                                skill(
                                        "payroll-auditor",
                                        Map.of(
                                                "references/faq.md", "# FAQ\n口径说明",
                                                "scripts/check.py", "print('ok')"))));

        String reply = tools.read("references/faq.md", null, context(new SkillReadBudget()));

        assertTrue(reply.contains("\"status\":\"OK\""), reply);
        assertTrue(reply.contains("\"skill_id\":\"payroll-auditor\""), reply);
        assertTrue(reply.contains("\"path\":\"references/faq.md\""), reply);
        assertTrue(reply.contains("口径说明"), reply);
        assertTrue(reply.contains("\"truncated\":false"), reply);
    }

    @Test
    @DisplayName("读取结果写入工具追溯，供会话展示数据来源")
    void recordsTraceEntry() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(
                                skill(
                                        "payroll-auditor",
                                        Map.of("references/faq.md", "# FAQ\n口径说明"))));
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("7")
                        .sessionId("s-1")
                        .put(SkillReadBudget.class, new SkillReadBudget())
                        .put(ToolTraceRecorder.class, recorder)
                        .build();

        tools.read("references/faq.md", null, context);

        ToolTraceRecorder.Entry entry = recorder.take(SkillReadTools.SKILL_READ);
        assertTrue(entry != null, "应写入 skill_read 追溯");
        assertTrue(entry.argsJson().contains("references/faq.md"), entry.argsJson());
        assertTrue(entry.resultJson().contains("口径说明"), entry.resultJson());
        assertTrue(entry.resultJson().contains("\"skill_id\":\"payroll-auditor\""), entry.resultJson());
    }

    @Test
    @DisplayName("越界路径与不存在的文件返回可读错误")
    void rejectsIllegalPath() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(skill("payroll-auditor", Map.of("references/faq.md", "# FAQ"))));

        String traversal = tools.read("references/../../secret.md", null, null);
        String absolute = tools.read("/etc/passwd", null, null);
        String missing = tools.read("references/none.md", null, null);

        assertTrue(traversal.contains(".."), traversal);
        assertTrue(absolute.contains("相对路径"), absolute);
        assertTrue(missing.contains("文件不存在"), missing);
    }

    @Test
    @DisplayName("多个技能含同名路径时要求传 skill_id")
    void requiresSkillIdForSharedPath() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(
                                skill("payroll-auditor", Map.of("references/faq.md", "审计口径")),
                                skill("payroll-policy", Map.of("references/faq.md", "制度口径"))));

        String ambiguous = tools.read("references/faq.md", null, null);
        String explicit = tools.read("references/faq.md", "payroll-policy", null);
        String unbound = tools.read("references/faq.md", "payroll-other", null);

        assertTrue(ambiguous.contains("skill_id"), ambiguous);
        assertTrue(explicit.contains("制度口径"), explicit);
        assertFalse(explicit.contains("审计口径"), explicit);
        assertTrue(unbound.contains("未绑定"), unbound);
    }

    @Test
    @DisplayName("单轮读取达到上限后拒绝，清零后恢复")
    void capsReadsPerTurn() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(skill("payroll-auditor", Map.of("references/faq.md", "# FAQ"))));
        SkillReadBudget budget = new SkillReadBudget(2);
        RuntimeContext context = context(budget);

        assertTrue(tools.read("references/faq.md", null, context).contains("\"status\":\"OK\""));
        assertTrue(tools.read("references/faq.md", null, context).contains("\"status\":\"OK\""));
        String third = tools.read("references/faq.md", null, context);
        assertTrue(third.contains("上限"), third);
        assertEquals(2, budget.used());

        budget.clear();
        assertTrue(tools.read("references/faq.md", null, context).contains("\"status\":\"OK\""));
    }

    @Test
    @DisplayName("超过 8KB 的内容截断并标记")
    void truncatesLargeContent() {
        SkillReadTools tools =
                new SkillReadTools(
                        List.of(
                                skill(
                                        "payroll-auditor",
                                        Map.of("references/long.md", "x".repeat(9_000)))));

        String reply = tools.read("references/long.md", null, null);

        assertTrue(reply.contains("\"truncated\":true"), reply);
        assertTrue(reply.contains("已截断"), reply);
        assertTrue(reply.length() < 9_000, "返回内容应被截断");
    }

    @Test
    @DisplayName("提示词仅在存在文件时列出路径")
    void guidanceFollowsResources() {
        AgentSkill withFiles =
                skill("payroll-auditor", Map.of("references/faq.md", "# FAQ"));
        AgentSkill withoutFiles = skill("payroll-policy", Map.of());

        String guidance = SkillReadTools.guidance(List.of(withFiles, withoutFiles));
        assertTrue(guidance.contains("skill_read(path)"), guidance);
        assertTrue(guidance.contains("references/faq.md"), guidance);
        assertTrue(guidance.contains("kb_search"), guidance);

        assertEquals("", SkillReadTools.guidance(List.of(withoutFiles)));
        assertEquals("", SkillReadTools.guidance(null));
    }

    private static RuntimeContext context(SkillReadBudget budget) {
        return RuntimeContext.builder()
                .userId("7")
                .sessionId("s-1")
                .put(SkillReadBudget.class, budget)
                .build();
    }

    private static AgentSkill skill(String name, Map<String, String> resources) {
        AgentSkill.Builder builder =
                AgentSkill.builder()
                        .name(name)
                        .description(name)
                        .skillContent("## 角色\n测试角色。")
                        .source("nacos");
        resources.forEach(builder::addResource);
        return builder.build();
    }
}
