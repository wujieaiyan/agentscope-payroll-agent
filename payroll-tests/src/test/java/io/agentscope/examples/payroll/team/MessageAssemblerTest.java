package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.service.AgentFlowExecutor;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import io.agentscope.examples.payroll.service.PayrollChatService.DynamicFlowResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageAssemblerTest {

    @Test
    @DisplayName("上游段落按角色标题渲染，并附带核算对象与追溯摘要")
    void rendersUpstreamMessages() {
        TeamMessage calculator =
                MessageAssembler.fromNode(
                        new TraceScope(11L, "plan-1", "n1", "calc-agent", "CALCULATOR", "SHADOW", null),
                        "n1",
                        "calc-agent",
                        "CALCULATOR",
                        "n2",
                        "EMP001 实发 8000.00",
                        List.of(new CalcTarget("EMP001", "2026-10")),
                        false,
                        List.of(TeamTestFixtures.trace(101L, 11L, "n1", "calc-agent", "call-1", "8000.00")));
        TeamMessage validator =
                MessageAssembler.fromNode(
                        new TraceScope(11L, "plan-1", "n2", "val-agent", "VALIDATOR", "SHADOW", null),
                        "n2",
                        "val-agent",
                        "VALIDATOR",
                        "",
                        "",
                        List.of(),
                        true,
                        List.of());

        String text = MessageAssembler.render("帮我核算 EMP001 的工资", List.of(calculator, validator));

        assertTrue(text.startsWith("【用户问题】\n帮我核算 EMP001 的工资"));
        assertTrue(text.contains("【计算智能体记录】\nEMP001 实发 8000.00"));
        assertTrue(text.contains("【验证智能体记录】\n（本节点未返回内容）"));
        assertTrue(text.contains("- EMP001 | 2026-10"));
        assertTrue(text.contains("- targets: 1"));
        assertTrue(text.contains("- toolError: true"));
        assertTrue(text.contains("- traceIds: 101"));
        assertTrue(text.endsWith("请按你的角色职责处理以上记录。"));
    }

    @Test
    @DisplayName("没有上游消息时原样返回用户问题")
    void passesQuestionThroughWithoutUpstream() {
        assertEquals("问题", MessageAssembler.render("问题", List.of()));
        assertEquals("", MessageAssembler.render(null, null));
    }

    @Test
    @DisplayName("标题优先使用角色名，未知角色回退节点 id")
    void resolvesTitles() {
        TeamMessage known =
                message("n1", "CALCULATOR", "n2", "", List.of(), false, List.of());
        assertEquals("计算智能体", MessageAssembler.title(known));

        TeamMessage unknown =
                message("n9", "CUSTOM", "", "", List.of(), false, List.of());
        assertEquals("CUSTOM", MessageAssembler.title(unknown));

        TeamMessage blank = message("n9", "", "", "", List.of(), false, List.of());
        assertEquals("n9", MessageAssembler.title(blank));
    }

    @Test
    @DisplayName("一次运行的节点消息按计划顺序组装，trace 按节点或智能体关联")
    void assemblesRunMessages() {
        TeamPlan plan =
                TeamPlan.instantiate(
                        "plan-7",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "calc-agent",
                                TeamRole.VALIDATOR, "val-agent"),
                        "",
                        List.of());
        TraceScope scope =
                new TraceScope(21L, "plan-7", null, null, null, "SHADOW", null);
        DynamicFlowResult result =
                new DynamicFlowResult(
                        "最终答复",
                        List.of(new CalcTarget("EMP001", "2026-10")),
                        List.of(stage("n1"), stage("n2")),
                        List.of(TeamTestFixtures.trace(201L, 21L, "n1", "calc-agent", "call-2", "8000.00")),
                        List.of("calculate_payroll"),
                        Map.of(
                                "n1",
                                new AgentFlowExecutor.NodeResult(
                                        "计算完成", List.of(new CalcTarget("EMP001", "2026-10")), false),
                                "n2",
                                new AgentFlowExecutor.NodeResult("复核通过", List.of(), false)));

        List<TeamMessage> messages =
                MessageAssembler.ofRun(
                        plan, scope, result, Map.of("n1", "CALCULATOR", "n2", "VALIDATOR"));

        assertEquals(2, messages.size());
        assertEquals("n1", messages.get(0).fromNodeId());
        assertEquals("CALCULATOR", messages.get(0).fromRole());
        assertEquals("计算完成", messages.get(0).text());
        assertEquals(List.of(201L), messages.get(0).traceIds());
        assertEquals("21", messages.get(0).runId());
        assertTrue(messages.get(0).versionSupported());
        assertEquals("n2", messages.get(1).fromNodeId());
        assertTrue(messages.get(1).traceIds().isEmpty());
    }

    @Test
    @DisplayName("附件中的金额只来自工具追溯，并限制文本长度")
    void buildsArtifactsFromTraces() {
        ChatToolTrace trace = TeamTestFixtures.trace(301L, 31L, "n1", "calc-agent", "call-3", "8000.00");
        TeamMessage message =
                MessageAssembler.fromNode(
                        null,
                        "n1",
                        "calc-agent",
                        "CALCULATOR",
                        "n2",
                        "x".repeat(TeamMessage.MAX_TEXT + 20),
                        List.of(),
                        true,
                        List.of(trace));

        assertEquals(TeamMessage.MAX_TEXT + 1, message.text().length());
        assertTrue(message.text().endsWith("…"));
        assertEquals("8000.00", message.artifacts().get("amounts") instanceof Map<?, ?> amounts
                ? amounts.get("net_pay")
                : null);
        assertEquals(1, message.artifacts().get("tool_calls"));
        assertEquals(true, message.artifacts().get("tool_error"));
        assertEquals("", message.runId());
        assertFalse(message.versionSupported() && message.schemaVersion().isBlank());
    }

    private static TeamMessage message(
            String nodeId,
            String role,
            String target,
            String text,
            List<CalcTarget> targets,
            boolean toolError,
            List<Long> traceIds) {
        return new TeamMessage(
                TeamMessage.SCHEMA_VERSION,
                "",
                "",
                nodeId,
                "",
                role,
                target,
                text,
                targets,
                toolError,
                traceIds,
                Map.of(),
                0L);
    }

    private static PayrollChat.StageRun stage(String nodeId) {
        return new PayrollChat.StageRun(
                nodeId, "stage-" + nodeId, "agent-" + nodeId, "CALCULATOR", "SUCCESS", 1, 0, true, 10, 20, null);
    }
}
