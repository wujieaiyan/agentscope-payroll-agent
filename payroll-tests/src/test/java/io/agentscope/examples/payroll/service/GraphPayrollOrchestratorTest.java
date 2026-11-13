package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import io.agentscope.examples.payroll.service.PayrollChatService.StageResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 图编排 POC：验证条件边分流与阶段顺序和手写编排一致，不依赖真实模型。 */
class GraphPayrollOrchestratorTest {

    private final PayrollAgentFactory.Team team =
            new PayrollAgentFactory.Team(null, null, null, null, null);
    private final Set<String> tools = new LinkedHashSet<>();

    @Test
    void skipsValidationAndAccountingWhenNoPayrollTarget() {
        List<String> stages = new ArrayList<>();
        List<String> events = new ArrayList<>();

        GraphPayrollOrchestrator.StageRunner stage =
                (key, label, agent, input, listener, answerStage, allTools) -> {
                    stages.add(key);
                    return new StageResult("员工清单：张伟", List.of(), 12);
                };
        GraphPayrollOrchestrator.Validator validator =
                (agent, question, calculation, listener, allTools) -> {
                    throw new AssertionError("查询类问题不应进入验证节点");
                };

        GraphPayrollOrchestrator.Outcome outcome =
                GraphPayrollOrchestrator.execute(
                        stage, validator, team, "有哪些员工？", "有哪些员工？", recorder(events), tools);

        assertEquals("员工清单：张伟", outcome.reply());
        assertEquals(List.of("calculator"), stages);
        assertEquals(List.of(), outcome.targets());
        assertTrue(events.contains("start:validator"));
        assertTrue(events.contains("end:validator"));
        assertTrue(events.contains("text:员工清单：张伟"));
    }

    @Test
    void runsAllThreeStagesInOrderWhenPayrollCalculated() {
        List<String> stages = new ArrayList<>();
        CalcTarget target = new CalcTarget("E1001", "2026-10");

        GraphPayrollOrchestrator.StageRunner stage =
                (key, label, agent, input, listener, answerStage, allTools) -> {
                    stages.add(key);
                    return switch (key) {
                        case "calculator" -> new StageResult("计算记录", List.of(target), 10);
                        case "accountant" -> new StageResult("最终答复", List.of(), 5);
                        default -> throw new AssertionError("未知阶段：" + key);
                    };
                };
        GraphPayrollOrchestrator.Validator validator =
                (agent, question, calculation, listener, allTools) -> {
                    stages.add("validator");
                    return "校验通过";
                };

        GraphPayrollOrchestrator.Outcome outcome =
                GraphPayrollOrchestrator.execute(
                        stage,
                        validator,
                        team,
                        "算下张伟 2026-10 工资",
                        "算下张伟 2026-10 工资",
                        ChatStreamListener.NOOP,
                        tools);

        assertEquals(List.of("calculator", "validator", "accountant"), stages);
        assertEquals("最终答复", outcome.reply());
        assertEquals(List.of(target), outcome.targets());
    }

    @Test
    void failsWhenAccountantReturnsBlank() {
        GraphPayrollOrchestrator.StageRunner stage =
                (key, label, agent, input, listener, answerStage, allTools) ->
                        "calculator".equals(key)
                                ? new StageResult(
                                        "计算记录",
                                        List.of(new CalcTarget("E1001", "2026-10")),
                                        10)
                                : new StageResult("  ", List.of(), 5);
        GraphPayrollOrchestrator.Validator validator =
                (agent, question, calculation, listener, allTools) -> "校验通过";

        assertThrows(
                PayrollException.class,
                () ->
                        GraphPayrollOrchestrator.execute(
                                stage,
                                validator,
                                team,
                                "算工资",
                                "算工资",
                                ChatStreamListener.NOOP,
                                tools));
    }

    private static ChatStreamListener recorder(List<String> events) {
        return new ChatStreamListener() {
            @Override
            public void onAgentStart(String agent, String label) {
                events.add("start:" + agent);
            }

            @Override
            public void onAgentEnd(String agent) {
                events.add("end:" + agent);
            }

            @Override
            public void onText(String delta) {
                events.add("text:" + delta);
            }
        };
    }
}
