package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import io.agentscope.examples.payroll.service.PayrollChatService.StageResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POC：用 langgraph4j 托管三阶段路由，节点内部仍复用 HarnessAgent 与工具。
 *
 * <p>与手写状态机的差异只有“谁决定下一节点”这一层：条件边根据计算阶段是否产生核算对象
 * 决定进入验证节点还是直接结束。金额计算、工具调用、SSE 事件与超时仍由
 * {@link PayrollChatService} 的既有阶段执行器负责，便于两条链路逐项对比。
 */
final class GraphPayrollOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphPayrollOrchestrator.class);

    private static final String CALCULATOR = "calculator";
    private static final String VALIDATOR = "validator";
    private static final String ACCOUNTANT = "accountant";
    private static final String SKIP = "skip";

    private GraphPayrollOrchestrator() {
    }

    /** 阶段执行器：签名与 PayrollChatService#runStage 一致，生产环境直接传方法引用。 */
    @FunctionalInterface
    interface StageRunner {
        StageResult run(
                String stageKey,
                String stageLabel,
                HarnessAgent agent,
                String input,
                ChatStreamListener listener,
                boolean answerStage,
                Set<String> tools);
    }

    /** 验证执行器：签名与 PayrollChatService#validate 一致。 */
    @FunctionalInterface
    interface Validator {
        String validate(
                HarnessAgent validator,
                String question,
                StageResult calculation,
                ChatStreamListener listener,
                Set<String> tools);
    }

    record Outcome(String reply, List<CalcTarget> targets, long elapsedMs) {
    }

    static Outcome execute(
            StageRunner stage,
            Validator validator,
            PayrollAgentFactory.Team team,
            String question,
            String calculatorInput,
            ChatStreamListener events,
            Set<String> tools) {
        long startedAt = System.currentTimeMillis();
        CompiledGraph<PayrollState> graph = compile(stage, validator, team, events, tools);
        PayrollState result;
        try {
            result =
                    graph.invoke(
                                    Map.of(
                                            "question", question,
                                            "calculator_input", calculatorInput))
                            .orElseThrow(() -> new PayrollException("图编排未产生最终状态"));
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
        String reply = result.reply();
        if (reply.isBlank()) {
            throw new PayrollException("核算智能体未返回内容，请重试或检查模型服务状态。");
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        List<CalcTarget> targets =
                result.calculation() == null ? List.of() : result.calculation().targets();
        log.info("[图编排] 流程结束：路由={}，核算对象={}，耗时={}ms", result.route(), targets.size(), elapsedMs);
        return new Outcome(reply, targets, elapsedMs);
    }

    private static CompiledGraph<PayrollState> compile(
            StageRunner stage,
            Validator validator,
            PayrollAgentFactory.Team team,
            ChatStreamListener events,
            Set<String> tools) {
        try {
            return new StateGraph<>(PayrollState::new)
                    .addNode(
                            CALCULATOR,
                            AsyncNodeAction.node_async(
                                    state ->
                                            calculatorNode(
                                                    state, stage, team, events, tools)))
                    .addEdge(GraphDefinition.START, CALCULATOR)
                    .addConditionalEdges(
                            CALCULATOR,
                            AsyncEdgeAction.edge_async(
                                    state -> state.needsValidation() ? VALIDATOR : SKIP),
                            Map.of(VALIDATOR, VALIDATOR, SKIP, GraphDefinition.END))
                    .addNode(
                            VALIDATOR,
                            AsyncNodeAction.node_async(
                                    state -> {
                                        String validation =
                                                validator.validate(
                                                        team.validator(),
                                                        state.question(),
                                                        state.calculation(),
                                                        events,
                                                        tools);
                                        log.info(
                                                "[图编排][validator] 验证完成：输出={}",
                                                preview(validation));
                                        return Map.of("validation", validation);
                                    }))
                    .addEdge(VALIDATOR, ACCOUNTANT)
                    .addNode(
                            ACCOUNTANT,
                            AsyncNodeAction.node_async(
                                    state -> accountantNode(state, stage, team, events, tools)))
                    .addEdge(ACCOUNTANT, GraphDefinition.END)
                    .compile();
        } catch (GraphStateException e) {
            throw new PayrollException("图编排构建失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> calculatorNode(
            PayrollState state,
            StageRunner stage,
            PayrollAgentFactory.Team team,
            ChatStreamListener events,
            Set<String> tools) {
        StageResult calculation =
                stage.run(
                        CALCULATOR,
                        "① 计算智能体：理解诉求并调用核算工具",
                        team.calculator(),
                        state.calculatorInput(),
                        events,
                        false,
                        tools);
        if (calculation.text().isBlank()) {
            throw new PayrollException("计算智能体未返回内容，请重试或检查模型服务状态。");
        }
        log.info(
                "[图编排][calculator] 计算完成：耗时={}ms，核算对象={}",
                calculation.elapsedMs(),
                calculation.targets().size());

        Map<String, Object> update = new HashMap<>();
        update.put("calculation", calculation);
        if (calculation.targets().isEmpty()) {
            // 查询类问题跳过后两个节点，但仍要补发验证阶段的跳过事件，保持前端时间线与手写编排一致。
            String validation = "本轮未执行工资计算，无需独立校验。";
            events.onAgentStart(VALIDATOR, "② 验证智能体：本轮无需校验");
            events.onAgentOutput(VALIDATOR, validation);
            events.onAgentEnd(VALIDATOR);
            update.put("validation", validation);
            String reply = calculation.text().trim();
            update.put("reply", reply);
            update.put("route", SKIP);
            events.onText(reply);
            log.info("[图编排] 条件边判定：无核算对象，跳过验证与汇总");
        } else {
            update.put("route", VALIDATOR);
        }
        return update;
    }

    private static Map<String, Object> accountantNode(
            PayrollState state,
            StageRunner stage,
            PayrollAgentFactory.Team team,
            ChatStreamListener events,
            Set<String> tools) {
        StageResult accounting =
                stage.run(
                        ACCOUNTANT,
                        "③ 核算智能体：汇总计算与验证结论",
                        team.accountant(),
                        PayrollChatService.accountingInput(
                                state.question(),
                                state.calculation().text(),
                                state.validation()),
                        events,
                        true,
                        tools);
        if (accounting.text().isBlank()) {
            throw new PayrollException("核算智能体未返回内容，请重试或检查模型服务状态。");
        }
        return Map.of("reply", accounting.text().trim(), "route", ACCOUNTANT);
    }

    /** 节点异常会被包装成 GraphRunnerException/CompletionException，这里还原出业务异常。 */
    private static RuntimeException unwrap(RuntimeException error) {
        Throwable current = error;
        PayrollException payroll = null;
        while (current != null) {
            if (current instanceof PayrollException candidate) {
                payroll = candidate;
            }
            current = current.getCause();
        }
        return payroll == null ? error : payroll;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
    }

    /** 图状态只承载阶段结果，所有业务数据仍来自工具与确定性引擎。 */
    static final class PayrollState extends AgentState {

        PayrollState(Map<String, Object> init) {
            super(init);
        }

        String question() {
            return value("question", "");
        }

        String calculatorInput() {
            return value("calculator_input", "");
        }

        StageResult calculation() {
            Object value = data().get("calculation");
            return value instanceof StageResult result ? result : null;
        }

        String validation() {
            return value("validation", "");
        }

        String reply() {
            return value("reply", "");
        }

        String route() {
            return value("route", "");
        }

        boolean needsValidation() {
            StageResult calculation = calculation();
            return calculation != null && !calculation.targets().isEmpty();
        }
    }
}
