package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理台对话实现：固定编排「计算智能体 → 验证智能体 → 核算智能体」三段流水线。
 *
 * <p>编排放在确定性 Java 代码中而不是交给模型路由，保证每一轮工资核算都会经过独立验证，
 * 且阶段顺序、超时与降级行为可预测。单机管理台只维护一个会话，串行处理请求。
 */
public final class PayrollChatService implements PayrollChat {

    private static final Logger log = LoggerFactory.getLogger(PayrollChatService.class);
    private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(150);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CALCULATION_TOOLS =
            Set.of("calculate_payroll", "calculate_department_payroll", "generate_payslip");
    private static final int BATCH_VALIDATION_LIMIT = 3;
    private static final int LOG_PREVIEW_LIMIT = 400;

    private final PayrollEngine engine;
    private final Object lock = new Object();
    private PayrollAgentFactory.Team team;

    public PayrollChatService(PayrollEngine engine) {
        this.engine = engine;
    }

    @Override
    public Reply chat(String message) {
        return run(message, null);
    }

    @Override
    public void stream(String message, ChatStreamListener listener) {
        Objects.requireNonNull(listener, "listener");
        listener.onStart(modelId());
        Reply reply = run(message, listener);
        listener.onDone(reply);
    }

    /**
     * 单轮对话：收集文本与工具名，同时把事件转发给流式监听器。
     *
     * <p>同一时间只允许一轮对话，避免共享的 ReActAgent 上下文交错。
     */
    private Reply run(String message, ChatStreamListener listener) {
        if (message == null || message.isBlank()) {
            throw new PayrollException("请输入要咨询的问题");
        }
        synchronized (lock) {
            PayrollAgentFactory.Team current = team();
            String question = message.trim();
            long startedAt = System.currentTimeMillis();
            Set<String> tools = new LinkedHashSet<>();
            log.info("[多智能体] 开始处理问题：{}", preview(question, 120));

            StageResult calculation;
            try {
                calculation =
                        runStage(
                                "calculator",
                                "① 计算智能体：理解诉求并调用核算工具",
                                current.calculator(),
                                question,
                                listener,
                                false,
                                tools);
            } catch (RuntimeException e) {
                throw chatFailure(e);
            }
            if (calculation.text().isBlank()) {
                throw new PayrollException("计算智能体未返回内容，请重试或检查模型服务状态。");
            }
            log.info(
                    "[多智能体] ① 计算完成：耗时={}ms，核算对象={}，输出={}",
                    calculation.elapsedMs(),
                    calculation.targets().size(),
                    preview(calculation.text(), LOG_PREVIEW_LIMIT));

            String validation;
            if (calculation.targets().isEmpty()) {
                validation = "本轮未执行工资计算，无需独立校验。";
                listener.onAgentStart("validator", "② 验证智能体：本轮无需校验");
                listener.onAgentOutput("validator", validation);
                listener.onAgentEnd("validator");
                log.info("[多智能体] ② 跳过验证：本轮没有工资计算对象");
            } else {
                validation =
                        validate(current.validator(), question, calculation, listener, tools);
                log.info(
                        "[多智能体] ② 验证完成：输出={}",
                        preview(validation, LOG_PREVIEW_LIMIT));
            }

            String reply;
            if (calculation.targets().isEmpty()) {
                // 查询类问题（员工清单/档案/政策/考勤）直接返回计算记录，避免小模型改写出错误的工资结论。
                reply = calculation.text().trim();
                listener.onText(reply);
                log.info("[多智能体] ③ 跳过核算汇总：查询类问题直接返回计算记录");
            } else {
                StageResult accounting;
                try {
                    accounting =
                            runStage(
                                    "accountant",
                                    "③ 核算智能体：汇总计算与验证结论",
                                    current.accountant(),
                                    accountingInput(question, calculation.text(), validation),
                                    listener,
                                    true,
                                    tools);
                } catch (RuntimeException e) {
                    throw chatFailure(e);
                }
                reply = accounting.text().trim();
                if (reply.isBlank()) {
                    throw new PayrollException("核算智能体未返回内容，请重试或检查模型服务状态。");
                }
            }
            log.info(
                    "[多智能体] 完成：总耗时={}ms，工具={}，答复长度={}",
                    System.currentTimeMillis() - startedAt,
                    tools,
                    reply.length());
            return new Reply(reply, List.copyOf(tools), modelId());
        }
    }

    private String validate(
            ReActAgent validator,
            String question,
            StageResult calculation,
            ChatStreamListener listener,
            Set<String> tools) {
        try {
            StageResult validation =
                    runStage(
                            "validator",
                            "② 验证智能体：独立复核勾稽关系",
                            validator,
                            validationInput(question, calculation.text(), calculation.targets()),
                            listener,
                            false,
                            tools);
            if (validation.text().isBlank()) {
                return "验证智能体未返回内容，本轮未完成独立验证。";
            }
            return validation.text();
        } catch (RuntimeException e) {
            log.error("[多智能体] ② 验证智能体执行失败，降级为未验证", e);
            return "验证智能体执行失败：" + rootMessage(e) + "。本轮未完成独立验证。";
        }
    }

    /**
     * 运行一个智能体阶段，把事件转发给监听器。
     *
     * @param answerStage true 表示该阶段文本是最终答复（推送到 text 事件），否则推送到 agent_text
     */
    private StageResult runStage(
            String stageKey,
            String stageLabel,
            ReActAgent agent,
            String input,
            ChatStreamListener listener,
            boolean answerStage,
            Set<String> allTools) {
        listener.onAgentStart(stageKey, stageLabel);
        long startedAt = System.currentTimeMillis();
        clearHistory(agent, stageKey);
        log.info("[多智能体][{}] 阶段开始：{}", stageKey, stageLabel);
        log.debug("[多智能体][{}] 输入：\n{}", stageKey, input);

        StringBuilder streamed = new StringBuilder();
        String[] finalText = {null};
        Map<String, StringBuilder> argsByCall = new LinkedHashMap<>();
        List<CalcTarget> targets = new ArrayList<>();
        Set<String> stageTools = new LinkedHashSet<>();

        try {
            agent.streamEvents(new UserMessage(input))
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolCallStartEvent call) {
                                    allTools.add(call.getToolCallName());
                                    stageTools.add(call.getToolCallName());
                                    argsByCall.putIfAbsent(
                                            call.getToolCallId(), new StringBuilder());
                                    listener.onToolStart(
                                            call.getToolCallId(), call.getToolCallName());
                                    log.info(
                                            "[多智能体][{}] 调用工具：{}",
                                            stageKey,
                                            call.getToolCallName());
                                } else if (event instanceof ToolCallDeltaEvent delta) {
                                    StringBuilder args =
                                            argsByCall.computeIfAbsent(
                                                    delta.getToolCallId(),
                                                    key -> new StringBuilder());
                                    if (delta.getDelta() != null) {
                                        args.append(delta.getDelta());
                                    }
                                    listener.onToolArgs(
                                            delta.getToolCallId(), delta.getDelta());
                                } else if (event instanceof ToolResultEndEvent result) {
                                    String args = argsOf(argsByCall, result.getToolCallId());
                                    collectTargets(result.getToolCallName(), args, targets);
                                    log.info(
                                            "[多智能体][{}] 工具结束：{}，state={}，参数={}",
                                            stageKey,
                                            result.getToolCallName(),
                                            result.getState(),
                                            preview(args, 300));
                                    listener.onToolResult(
                                            result.getToolCallId(),
                                            result.getToolCallName(),
                                            result.getState().name());
                                } else if (event instanceof TextBlockDeltaEvent delta) {
                                    streamed.append(delta.getDelta());
                                    if (answerStage) {
                                        listener.onText(delta.getDelta());
                                    } else {
                                        listener.onAgentOutput(stageKey, delta.getDelta());
                                    }
                                } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                                    listener.onThinking(delta.getDelta());
                                } else if (event instanceof AgentResultEvent result
                                        && result.getResult() != null) {
                                    finalText[0] = result.getResult().getTextContent();
                                } else if (event instanceof ExceedMaxItersEvent) {
                                    listener.onPhase(stageLabel + " · 已达到最大工具调用轮次");
                                    log.warn("[多智能体][{}] 达到最大工具调用轮次", stageKey);
                                }
                            })
                    .blockLast(STAGE_TIMEOUT);
        } finally {
            log.info(
                    "[多智能体][{}] 阶段结束：耗时={}ms，工具={}",
                    stageKey,
                    System.currentTimeMillis() - startedAt,
                    stageTools);
            listener.onAgentEnd(stageKey);
        }

        String text =
                finalText[0] != null && !finalText[0].isBlank()
                        ? finalText[0].trim()
                        : streamed.toString().trim();
        if (streamed.length() == 0 && !text.isBlank()) {
            if (answerStage) {
                listener.onText(text);
            } else {
                listener.onAgentOutput(stageKey, text);
            }
        }
        log.debug("[多智能体][{}] 输出：\n{}", stageKey, text);
        return new StageResult(text, List.copyOf(targets), System.currentTimeMillis() - startedAt);
    }

    /**
     * 每轮阶段开始前清空历史上下文，避免模型复述上一轮缓存的员工清单或金额。
     *
     * <p>主数据可能在对话期间被管理台修改；保留历史会让模型跳过工具调用，直接使用过期结果。
     */
    private static void clearHistory(ReActAgent agent, String stageKey) {
        try {
            agent.clearContext(agent.getRuntimeContext());
            log.debug("[多智能体][{}] 已清理历史上下文", stageKey);
        } catch (RuntimeException e) {
            log.warn("[多智能体][{}] 清理历史上下文失败：{}", stageKey, rootMessage(e));
        }
    }

    private static String validationInput(
            String question, String calculation, List<CalcTarget> targets) {
        String objects =
                targets.stream()
                        .map(target -> "- " + target.employeeId() + " | " + target.period())
                        .collect(Collectors.joining("\n"));
        return """
                【用户问题】
                %s

                【计算智能体记录】
                %s

                【核算对象（必须逐个调用 validate_payroll，最多处理前 3 个）】
                %s

                请按你的角色输出验证记录，不要给最终用户答复。
                """
                .formatted(question, calculation, objects);
    }

    private static String accountingInput(String question, String calculation, String validation) {
        return """
                【用户问题】
                %s

                【计算智能体记录】
                %s

                【验证智能体记录】
                %s

                请基于以上两份记录生成最终 HR 答复。不得改动任何金额；验证不通过时必须明确提示暂不可发薪。
                """
                .formatted(question, calculation, validation);
    }

    /** 从工具参数里提取需要验证的员工与期间；批量核算会展开为最多 3 名员工做抽检。 */
    private void collectTargets(String toolName, String argsJson, List<CalcTarget> targets) {
        if (toolName == null || argsJson == null || argsJson.isBlank()) {
            return;
        }
        JsonNode args;
        try {
            args = JSON.readTree(argsJson);
        } catch (JsonProcessingException e) {
            log.debug("[多智能体] 无法解析工具参数：{}", preview(argsJson, 200));
            return;
        }
        String period = text(args, "period");
        if (period.isBlank()) {
            return;
        }
        switch (toolName) {
            case "calculate_payroll", "generate_payslip" ->
                    addTarget(targets, text(args, "employee_id"), period);
            case "calculate_department_payroll" ->
                    addDepartmentTargets(targets, text(args, "department"), period);
            default -> {
                if (CALCULATION_TOOLS.contains(toolName)) {
                    log.debug("[多智能体] 未识别的核算工具参数：{}", toolName);
                }
            }
        }
    }

    private void addDepartmentTargets(
            List<CalcTarget> targets, String department, String period) {
        String scope = department == null ? "" : department.trim();
        List<Employee> employees =
                engine.employees().stream()
                        .filter(
                                employee ->
                                        scope.isEmpty()
                                                || scope.equals("全部")
                                                || employee.department().equals(scope))
                        .limit(BATCH_VALIDATION_LIMIT)
                        .toList();
        if (employees.isEmpty()) {
            log.warn("[多智能体] 批量核算未匹配到可抽检员工，部门={}", scope);
            return;
        }
        for (Employee employee : employees) {
            addTarget(targets, employee.employeeId(), period);
        }
    }

    private static void addTarget(List<CalcTarget> targets, String employeeId, String period) {
        if (employeeId == null || employeeId.isBlank() || period == null || period.isBlank()) {
            return;
        }
        CalcTarget target = new CalcTarget(employeeId.trim(), period.trim());
        if (!targets.contains(target)) {
            targets.add(target);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static String argsOf(Map<String, StringBuilder> argsByCall, String toolCallId) {
        StringBuilder args = argsByCall.get(toolCallId);
        return args == null ? "" : args.toString();
    }

    @Override
    public String modelId() {
        return PayrollAgentFactory.modelId();
    }

    @Override
    public void reset() {
        synchronized (lock) {
            if (team != null) {
                team.close();
                team = null;
                log.info("[多智能体] 会话已重置");
            }
        }
    }

    @Override
    public void close() {
        reset();
    }

    private PayrollAgentFactory.Team team() {
        if (team == null) {
            long startedAt = System.currentTimeMillis();
            team = PayrollAgentFactory.createTeam(new PayrollTools(engine));
            log.info(
                    "[多智能体] 智能体初始化耗时 {} ms",
                    System.currentTimeMillis() - startedAt);
        }
        return team;
    }

    private static PayrollException chatFailure(RuntimeException e) {
        if (e instanceof PayrollException payrollException) {
            return payrollException;
        }
        return new PayrollException(
                "对话失败："
                        + rootMessage(e)
                        + "。请确认模型服务可用：默认需要先执行 ollama serve，"
                        + "并已拉取 PAYROLL_MODEL 指定的模型；也可配置 DashScope。",
                e);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= limit ? flattened : flattened.substring(0, limit) + "…";
    }

    private record CalcTarget(String employeeId, String period) {
    }

    private record StageResult(String text, List<CalcTarget> targets, long elapsedMs) {
    }
}
