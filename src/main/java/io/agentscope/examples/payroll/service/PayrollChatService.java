package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.examples.payroll.agent.ToolTraceRecorder;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.support.ToolTraceExtractor;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.repository.ChatContextStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
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
    private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CALCULATION_TOOLS =
            Set.of("calculate_payroll", "calculate_department_payroll", "generate_payslip");
    private static final int BATCH_VALIDATION_LIMIT = 3;
    private static final int LOG_PREVIEW_LIMIT = 400;

    private final PayrollEngine engine;
    private final ChatContextStore contextStore;
    private final long sessionId;
    private final long userId;
    private final boolean graphOrchestration;
    private final VectorMemoryService memory;
    private final Supplier<AgentDefinition> activeAgentSupplier;
    private final Supplier<FlowDefinition> activeFlowSupplier;
    private final Supplier<String> activeFlowIdSupplier;
    private final Supplier<List<AgentDefinition>> agentCatalogSupplier;
    private final Object lock = new Object();
    private final ToolTraceRecorder traceRecorder = new ToolTraceRecorder();
    private final List<ChatToolTrace> collectedTraces = new CopyOnWriteArrayList<>();
    private final RuntimeContext sessionContext;
    private PayrollAgentFactory.Team team;
    private ConversationContext context;

    public PayrollChatService(PayrollEngine engine) {
        this(engine, ChatContextStore.noop(), 0L, 0L, false, VectorMemoryService.disabled());
    }

    public PayrollChatService(PayrollEngine engine, ChatContextStore contextStore, long sessionId) {
        this(engine, contextStore, sessionId, 0L, false, VectorMemoryService.disabled());
    }

    public PayrollChatService(
            PayrollEngine engine,
            ChatContextStore contextStore,
            long sessionId,
            boolean graphOrchestration) {
        this(
                engine,
                contextStore,
                sessionId,
                0L,
                graphOrchestration,
                VectorMemoryService.disabled());
    }

    public PayrollChatService(
            PayrollEngine engine,
            ChatContextStore contextStore,
            long sessionId,
            long userId,
            boolean graphOrchestration,
            VectorMemoryService memory) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                () -> null);
    }

    /**
     * @param activeAgentSupplier 每轮读取管理员配置的全局核算智能体；返回 null 时使用默认三段流水线
     */
    public PayrollChatService(
            PayrollEngine engine,
            ChatContextStore contextStore,
            long sessionId,
            long userId,
            boolean graphOrchestration,
            VectorMemoryService memory,
            Supplier<AgentDefinition> activeAgentSupplier) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                activeAgentSupplier,
                () -> null,
                () -> "",
                () -> List.of());
    }

    /**
     * @param activeFlowSupplier 每轮读取全局默认流程；null 表示未启用流程编排
     * @param activeFlowIdSupplier 读取已配置的默认流程 id，用于区分“未配置”和“配置了但暂不可用”
     * @param agentCatalogSupplier 读取智能体目录，用于校验流程节点引用与执行时解析定义
     */
    public PayrollChatService(
            PayrollEngine engine,
            ChatContextStore contextStore,
            long sessionId,
            long userId,
            boolean graphOrchestration,
            VectorMemoryService memory,
            Supplier<AgentDefinition> activeAgentSupplier,
            Supplier<FlowDefinition> activeFlowSupplier,
            Supplier<String> activeFlowIdSupplier,
            Supplier<List<AgentDefinition>> agentCatalogSupplier) {
        this.engine = engine;
        this.contextStore = contextStore;
        this.sessionId = sessionId;
        this.userId = userId;
        this.graphOrchestration = graphOrchestration;
        this.memory = memory == null ? VectorMemoryService.disabled() : memory;
        this.activeAgentSupplier = activeAgentSupplier;
        this.activeFlowSupplier = activeFlowSupplier;
        this.activeFlowIdSupplier = activeFlowIdSupplier;
        this.agentCatalogSupplier = agentCatalogSupplier;
        this.sessionContext =
                RuntimeContext.builder()
                        .userId(String.valueOf(userId))
                        .sessionId(String.valueOf(sessionId))
                        .put(ToolTraceRecorder.class, traceRecorder)
                        .build();
        this.context = loadContext(contextStore, sessionId);
    }

    @Override
    public Reply chat(String message) {
        return run(message, ChatStreamListener.NOOP);
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
     * <p>同一时间只允许一轮对话，避免共享的 HarnessAgent 上下文交错。
     */
    private Reply run(String message, ChatStreamListener listener) {
        if (message == null || message.isBlank()) {
            throw new PayrollException("请输入要咨询的问题");
        }
        ChatStreamListener events = listener == null ? ChatStreamListener.NOOP : listener;
        synchronized (lock) {
            PayrollAgentFactory.Team current = team();
            String question = message.trim();
            long startedAt = System.currentTimeMillis();
            Set<String> tools = Collections.synchronizedSet(new LinkedHashSet<>());
            collectedTraces.clear();
            traceRecorder.clear();
            log.info("[多智能体] 开始处理问题：{}", preview(question, 120));

            ResolvedFlow resolvedFlow = resolveActiveFlow();
            String persona =
                    resolvedFlow != null
                            ? "flow:" + resolvedFlow.flow().id()
                            : current.activeAgent() == null
                                    ? "pipeline"
                                    : "agent:" + current.activeAgent().id();
            applyPersona(persona, events);

            prepareContext(context, events, current.summarizer());
            String memoryBlock = memory.recall(userId, sessionId, question);
            if (!memoryBlock.isBlank()) {
                log.info("[向量] 已注入历史线索：{} 字", memoryBlock.length());
            }
            String calculatorInput = context.augment(question, memoryBlock);

            String reply;
            if (resolvedFlow != null) {
                reply = flowReply(calculatorInput, resolvedFlow, events, tools);
            } else if (!current.pipeline()) {
                reply = activeAgentReply(calculatorInput, current, events, tools);
            } else if (graphOrchestration) {
                reply = graphReply(question, calculatorInput, current, events, tools);
            } else {
                reply = legacyReply(question, calculatorInput, current, events, tools);
            }
            context.append(question, reply);
            log.info(
                    "[多智能体] 完成：总耗时={}ms，工具={}，答复长度={}，上下文=摘要{}轮+最近{}轮",
                    System.currentTimeMillis() - startedAt,
                    tools,
                    reply.length(),
                    context.summarizedTurns(),
                    context.recentTurnCount());
            log.info(
                    "[多智能体] 工具追溯汇总：{} 次调用，金额类 {} 次",
                    collectedTraces.size(),
                    collectedTraces.stream().filter(trace -> !trace.amounts().isEmpty()).count());
            return new Reply(
                    reply, List.copyOf(tools), modelId(), List.copyOf(collectedTraces));
        }
    }

    /** 原有确定性流水线：计算 → 验证 → 核算，阶段顺序写死在代码里。 */
    private String legacyReply(
            String question,
            String calculatorInput,
            PayrollAgentFactory.Team current,
            ChatStreamListener events,
            Set<String> tools) {
        StageResult calculation;
        try {
            calculation =
                    runStage(
                            "calculator",
                            "① 计算智能体：理解诉求并调用核算工具",
                            current.calculator(),
                            calculatorInput,
                            events,
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
            events.onAgentStart("validator", "② 验证智能体：本轮无需校验");
            events.onAgentOutput("validator", validation);
            events.onAgentEnd("validator");
            log.info("[多智能体] ② 跳过验证：本轮没有工资计算对象");
        } else {
            validation = validate(current.validator(), question, calculation, events, tools);
            log.info("[多智能体] ② 验证完成：输出={}", preview(validation, LOG_PREVIEW_LIMIT));
        }

        if (calculation.targets().isEmpty()) {
            // 查询类问题（员工清单/档案/政策/考勤）直接返回计算记录，避免小模型改写出错误的工资结论。
            String reply = calculation.text().trim();
            events.onText(reply);
            log.info("[多智能体] ③ 跳过核算汇总：查询类问题直接返回计算记录");
            return reply;
        }

        StageResult accounting;
        try {
            accounting =
                    runStage(
                            "accountant",
                            "③ 核算智能体：汇总计算与验证结论",
                            current.accountant(),
                            accountingInput(question, calculation.text(), validation),
                            events,
                            true,
                            tools);
        } catch (RuntimeException e) {
            throw chatFailure(e);
        }
        String reply = accounting.text().trim();
        if (reply.isBlank()) {
            throw new PayrollException("核算智能体未返回内容，请重试或检查模型服务状态。");
        }
        return reply;
    }

    /** 管理员选择了非计算类智能体：跳过固定流水线，由该智能体直接完成本轮核算并作答。 */
    private String activeAgentReply(
            String input,
            PayrollAgentFactory.Team current,
            ChatStreamListener events,
            Set<String> tools) {
        AgentDefinition agent = current.activeAgent();
        String label = agent == null ? "核算智能体" : "核算智能体：" + agent.name();
        StageResult result;
        try {
            result =
                    runStage(
                            "active",
                            label,
                            current.calculator(),
                            input,
                            events,
                            true,
                            tools);
        } catch (RuntimeException e) {
            throw chatFailure(e);
        }
        String reply = result.text().trim();
        if (reply.isBlank()) {
            throw new PayrollException("核算智能体未返回内容，请重试或检查模型服务状态。");
        }
        return reply;
    }

    /**
     * 读取全局默认流程。
     *
     * <p>未配置默认流程时才允许走既有薪酬链路；一旦配置了流程却无法解析（Nacos 抖动、智能体
     * 未加载、校验失败），必须直接报错，禁止静默回退到薪酬计算智能体，否则自定义角色会话会
     * 收到完全无关的工资回答。
     */
    private ResolvedFlow resolveActiveFlow() {
        if (activeFlowSupplier == null || agentCatalogSupplier == null) {
            return null;
        }
        String configuredId = "";
        if (activeFlowIdSupplier != null) {
            try {
                configuredId = activeFlowIdSupplier.get();
            } catch (RuntimeException e) {
                throw new PayrollException(
                        "读取默认流程配置失败，已阻止回退薪酬链路：" + rootMessage(e), e);
            }
            configuredId = configuredId == null ? "" : configuredId.trim();
        }
        FlowDefinition flow;
        try {
            flow = activeFlowSupplier.get();
        } catch (RuntimeException e) {
            if (!configuredId.isBlank()) {
                throw new PayrollException(
                        "默认流程「" + configuredId + "」暂不可用，已阻止回退薪酬链路："
                                + rootMessage(e),
                        e);
            }
            log.warn("[流程编排] 读取默认流程失败，回退既有链路：{}", rootMessage(e));
            return null;
        }
        if (flow == null) {
            if (!configuredId.isBlank()) {
                throw new PayrollException(
                        "默认流程「" + configuredId
                                + "」暂不可用（流程或智能体未加载），已阻止回退薪酬链路，请稍后重试");
            }
            return null;
        }
        List<AgentDefinition> catalog;
        try {
            catalog = agentCatalogSupplier.get();
        } catch (RuntimeException e) {
            throw new PayrollException(
                    "读取智能体目录失败，已阻止回退薪酬链路：" + rootMessage(e), e);
        }
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (AgentDefinition agent : catalog) {
            agents.put(agent.id(), agent);
        }
        List<String> errors = FlowValidator.validate(flow, agents.keySet());
        if (!errors.isEmpty()) {
            log.warn("[流程编排] 默认流程 {} 校验失败：{}", flow.id(), errors);
            throw new PayrollException(
                    "默认流程「" + flow.name() + "」校验失败，已阻止回退薪酬链路："
                            + String.join("；", errors));
        }
        for (FlowNode node : flow.nodes()) {
            if (!FlowNode.TYPE_AGENT.equalsIgnoreCase(node.type())) {
                continue;
            }
            AgentDefinition agent = agents.get(node.agentId());
            if (agent == null || !AgentDefinition.STATUS_ONLINE.equals(agent.status())) {
                log.warn(
                        "[流程编排] 节点 {} 引用的智能体 {} 不可用",
                        node.id(),
                        node.agentId());
                throw new PayrollException(
                        "默认流程「" + flow.name() + "」节点「" + node.label()
                                + "」引用的智能体不可用，已阻止回退薪酬链路；请检查 Agent 管理或稍后重试");
            }
        }
        log.info(
                "[流程编排] 使用全局默认流程：{}（{}），节点={}，同层并行",
                flow.name(),
                flow.id(),
                flow.nodes().size());
        return new ResolvedFlow(flow, Map.copyOf(agents));
    }

    /**
     * 会话与当前角色绑定：管理员切换默认流程/智能体后，上一角色的对话上下文与向量线索
     * 都不能继续注入，否则情感专家会看到“武杰的工资”这类历史问题并给出串线回答。
     */
    void applyPersona(String persona, ChatStreamListener events) {
        if (sessionId <= 0 || persona == null || persona.isBlank()) {
            return;
        }
        String previous;
        try {
            previous = contextStore.persona(sessionId);
        } catch (RuntimeException e) {
            log.warn("[会话] 读取角色绑定失败：sessionId={}，原因={}", sessionId, rootMessage(e));
            return;
        }
        if (persona.equals(previous)) {
            return;
        }
        if (previous != null && !previous.isBlank()) {
            context = new ConversationContext("", 0);
            persistContext(context);
            memory.onPersonaChanged(userId, sessionId);
            events.onPhase("检测到智能体切换，已隔离上一角色的会话上下文");
            log.info(
                    "[会话] 角色切换：sessionId={}，{} -> {}，已清空会话上下文",
                    sessionId,
                    previous,
                    persona);
        }
        try {
            contextStore.savePersona(sessionId, persona);
        } catch (RuntimeException e) {
            log.warn("[会话] 保存角色绑定失败：sessionId={}，原因={}", sessionId, rootMessage(e));
        }
    }

    private String flowReply(
            String baseInput,
            ResolvedFlow resolved,
            ChatStreamListener events,
            Set<String> tools) {
        ChatStreamListener synchronizedEvents = new SynchronizedChatStreamListener(events);
        AgentFlowExecutor.Outcome outcome;
        try {
            outcome =
                    AgentFlowExecutor.execute(
                            resolved.flow(),
                            baseInput,
                            synchronizedEvents,
                            (node, input, listener) ->
                                    executeFlowNode(
                                            node,
                                            resolved.agents().get(node.agentId()),
                                            input,
                                            listener,
                                            tools));
        } catch (RuntimeException e) {
            throw chatFailure(e);
        }
        String reply = outcome.reply().trim();
        if (reply.isBlank()) {
            throw new PayrollException("流程执行完成但没有产生答复内容，请检查智能体节点");
        }
        events.onText(reply);
        log.info(
                "[流程编排] 最终答复：流程={}，有效节点={}，核算对象={}",
                resolved.flow().id(),
                outcome.nodeResults().size(),
                outcome.targets().size());
        return reply;
    }

    /** 每个节点使用独立的调用上下文与追溯记录器；智能体实例按定义复用。 */
    private AgentFlowExecutor.NodeResult executeFlowNode(
            FlowNode node,
            AgentDefinition agent,
            String input,
            ChatStreamListener listener,
            Set<String> allTools) {
        if (agent == null) {
            throw new PayrollException("流程节点引用的智能体不存在：" + node.agentId());
        }
        String label = node.label();
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        RuntimeContext nodeContext = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId + ":" + node.id())
                .put(ToolTraceRecorder.class, recorder)
                .build();
        PayrollAgentFactory.NodeAgent runtime =
                PayrollAgentFactory.sharedFlowNodeAgent(engine, agent);
        try {
            StageResult result =
                    runStage(
                            node.id(),
                            label,
                            runtime.agent(),
                            input,
                            listener,
                            false,
                            allTools,
                            recorder,
                            nodeContext);
            return new AgentFlowExecutor.NodeResult(
                    result.text(), result.targets(), result.toolError());
        } catch (PayrollException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PayrollException(
                    "流程节点「" + label + "」执行失败：" + rootMessage(e), e);
        } finally {
            runtime.agent().clearContext(nodeContext);
        }
    }

    /** POC：由 langgraph4j 决定阶段流转，节点内部仍复用 HarnessAgent 与工具。 */
    private String graphReply(
            String question,
            String calculatorInput,
            PayrollAgentFactory.Team current,
            ChatStreamListener events,
            Set<String> tools) {
        try {
            return GraphPayrollOrchestrator.execute(
                            this::runStage,
                            this::validate,
                            current,
                            question,
                            calculatorInput,
                            events,
                            tools)
                    .reply();
        } catch (RuntimeException e) {
            throw chatFailure(e);
        }
    }

    private String validate(
            HarnessAgent validator,
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

    private StageResult runStage(
            String stageKey,
            String stageLabel,
            HarnessAgent agent,
            String input,
            ChatStreamListener listener,
            boolean answerStage,
            Set<String> allTools) {
        return runStage(
                stageKey,
                stageLabel,
                agent,
                input,
                listener,
                answerStage,
                allTools,
                traceRecorder,
                sessionContext);
    }

    /**
     * 运行一个智能体阶段，把事件转发给监听器。
     *
     * @param answerStage true 表示该阶段文本是最终答复（推送到 text 事件），否则推送到 agent_text
     * @param recorder 该阶段专用追溯记录器；流程并行节点各自持有一个，避免结果串线
     * @param callContext 本次调用的会话上下文；共享智能体依赖它隔离历史与工具状态
     */
    private StageResult runStage(
            String stageKey,
            String stageLabel,
            HarnessAgent agent,
            String input,
            ChatStreamListener listener,
            boolean answerStage,
            Set<String> allTools,
            ToolTraceRecorder recorder,
            RuntimeContext callContext) {
        listener.onAgentStart(stageKey, stageLabel);
        long startedAt = System.currentTimeMillis();
        callContext.put(ToolTraceRecorder.class, recorder);
        clearHistory(agent, callContext, stageKey);
        log.info("[多智能体][{}] 阶段开始：{}", stageKey, stageLabel);
        log.debug("[多智能体][{}] 输入：\n{}", stageKey, input);

        StringBuilder streamed = new StringBuilder();
        String[] finalText = {null};
        Map<String, StringBuilder> argsByCall = new LinkedHashMap<>();
        List<CalcTarget> targets = new ArrayList<>();
        Set<String> stageTools = new LinkedHashSet<>();
        boolean[] toolError = {false};

        try {
            agent.streamEvents(new UserMessage(input), callContext)
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
                                    if (!"SUCCESS".equals(result.getState().name())) {
                                        toolError[0] = true;
                                    }
                                    ChatToolTrace trace =
                                            traceOf(recorder, stageKey, result, args);
                                    collectedTraces.add(trace);
                                    log.info(
                                            "[多智能体][{}] 工具结束：{}，state={}，耗时={}ms，参数={}，金额={}，员工={}，期间={}",
                                            stageKey,
                                            result.getToolCallName(),
                                            result.getState(),
                                            trace.durationMs(),
                                            preview(args, 300),
                                            trace.amounts(),
                                            trace.employees(),
                                            trace.period());
                                    listener.onToolResult(
                                            result.getToolCallId(),
                                            result.getToolCallName(),
                                            result.getState().name(),
                                            trace);
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
            recorder.clear();
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
        return new StageResult(
                text, List.copyOf(targets), System.currentTimeMillis() - startedAt, toolError[0]);
    }

    /**
     * 每轮阶段开始前清空历史上下文，避免模型复述上一轮缓存的员工清单或金额。
     *
     * <p>跨轮记忆改由 {@link ConversationContext} 以“摘要 + 最近轮次”注入本轮输入，
     * 既能解析指代，又不会把历史金额带进工具调用。
     */
    private static void clearHistory(
            HarnessAgent agent, RuntimeContext context, String stageKey) {
        try {
            agent.clearContext(context);
            log.debug("[多智能体][{}] 已清理历史上下文", stageKey);
        } catch (RuntimeException e) {
            log.warn("[多智能体][{}] 清理历史上下文失败：{}", stageKey, rootMessage(e));
        }
    }

    /** 达到阈值时把较早轮次交给压缩器折叠成摘要；压缩失败只丢弃最早轮次，不阻断本轮回答。 */
    private void prepareContext(
            ConversationContext conversation, ChatStreamListener listener, HarnessAgent summarizer) {
        if (conversation.isEmpty()) {
            return;
        }
        if (!conversation.needsCompression()) {
            return;
        }
        List<ConversationContext.Turn> batch = conversation.compressionBatch();
        if (batch.isEmpty()) {
            return;
        }
        listener.onPhase("正在压缩会话上下文（" + batch.size() + " 轮）…");
        long startedAt = System.currentTimeMillis();
        try {
            String summary = summarize(conversation, batch, summarizer);
            if (summary.isBlank()) {
                throw new PayrollException("上下文压缩结果为空");
            }
            conversation.applySummary(summary, batch.size());
            persistContext(conversation);
            memory.indexSummary(
                    userId,
                    sessionId,
                    conversation.summarizedTurns(),
                    conversation.summary());
            listener.onContextCompressed(
                    batch.size(),
                    conversation.recentTurnCount(),
                    conversation.summarizedTurns());
            log.info(
                    "[上下文] 压缩完成：折叠 {} 轮，保留 {} 轮，摘要 {} 字，耗时={}ms",
                    batch.size(),
                    conversation.recentTurnCount(),
                    conversation.summary().length(),
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            conversation.discardOldest(
                    batch.size(), "（早期 " + batch.size() + " 轮对话因压缩失败已省略）");
            persistContext(conversation);
            log.warn(
                    "[上下文] 压缩失败，降级为丢弃最早 {} 轮：{}",
                    batch.size(),
                    rootMessage(e));
        }
    }

    private String summarize(
            ConversationContext conversation,
            List<ConversationContext.Turn> batch,
            HarnessAgent summarizer) {
        clearHistory(summarizer, sessionContext, "summarizer");
        String prompt = ConversationContext.summaryPrompt(conversation.summary(), batch);
        log.debug("[上下文] 压缩输入：\n{}", prompt);
        Msg result = summarizer.call(prompt, sessionContext).block(SUMMARY_TIMEOUT);
        return result == null || result.getTextContent() == null
                ? ""
                : result.getTextContent().trim();
    }

    private void persistContext(ConversationContext conversation) {
        if (sessionId <= 0) {
            return;
        }
        try {
            contextStore.saveContext(sessionId, conversation.snapshot());
        } catch (RuntimeException e) {
            log.warn("[上下文] 保存摘要失败：sessionId={}，原因={}", sessionId, rootMessage(e));
        }
    }

    private static ConversationContext loadContext(ChatContextStore store, long sessionId) {
        if (sessionId <= 0) {
            return new ConversationContext("", 0);
        }
        try {
            return ConversationContext.from(store.loadContext(sessionId), store.messages(sessionId));
        } catch (RuntimeException e) {
            log.warn(
                    "[上下文] 加载失败，按空上下文继续：sessionId={}，原因={}",
                    sessionId,
                    rootMessage(e));
            return new ConversationContext("", 0);
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

    static String accountingInput(String question, String calculation, String validation) {
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

    /**
     * 内置工具优先使用采集到的参数与结果；MCP 等外部工具没有采集记录，退化为事件里的参数。
     */
    private ChatToolTrace traceOf(
            ToolTraceRecorder recorder, String stageKey, ToolResultEndEvent result, String args) {
        ToolTraceRecorder.Entry entry = recorder.take(result.getToolCallName());
        if (entry != null) {
            return ChatToolTrace.draft(
                    result.getToolCallId(),
                    entry.toolName(),
                    stageKey,
                    entry.argsJson(),
                    entry.resultJson(),
                    entry.truncated(),
                    result.getState().name(),
                    entry.durationMs(),
                    entry.employees(),
                    entry.period(),
                    entry.department(),
                    entry.amounts());
        }
        ToolTraceExtractor.Evidence evidence = ToolTraceExtractor.extract(args, null);
        return ChatToolTrace.draft(
                result.getToolCallId(),
                result.getToolCallName(),
                stageKey,
                args,
                null,
                false,
                result.getState().name(),
                0,
                evidence.employees(),
                evidence.period(),
                evidence.department(),
                evidence.amounts());
    }

    @Override
    public String modelId() {
        return PayrollAgentFactory.modelId();
    }

    @Override
    public void reset() {
        synchronized (lock) {
            context = new ConversationContext("", 0);
            collectedTraces.clear();
            traceRecorder.clear();
            persistContext(context);
            if (team != null) {
                // 智能体实例是进程级共享的，只能清理本会话上下文，不能关闭。
                team.clearSession(sessionContext);
                team = null;
                log.info("[多智能体] 会话已重置，共享智能体实例保留");
            }
        }
    }

    @Override
    public void close() {
        reset();
    }

    /**
     * 按智能体定义取进程级缓存实例；管理员修改定义后签名变化，下一轮自动切换到新实例。
     */
    private PayrollAgentFactory.Team team() {
        AgentDefinition active = resolveActiveAgent();
        long startedAt = System.currentTimeMillis();
        PayrollAgentFactory.Team current = PayrollAgentFactory.sharedTeam(engine, active);
        if (team != current) {
            log.info(
                    "[多智能体] 智能体缓存就绪：{}，模式={}，缓存条目={}，耗时={}ms",
                    describe(active),
                    current.pipeline() ? "三段流水线" : "单智能体",
                    PayrollAgentFactory.cachedAgentCount(),
                    System.currentTimeMillis() - startedAt);
            team = current;
        } else {
            log.debug("[多智能体] 复用已缓存智能体：{}", describe(active));
        }
        return current;
    }

    private AgentDefinition resolveActiveAgent() {
        if (activeAgentSupplier == null) {
            return null;
        }
        try {
            return activeAgentSupplier.get();
        } catch (RuntimeException e) {
            log.warn("[Agent] 读取核算智能体配置失败，回退默认流水线：{}", rootMessage(e));
            return null;
        }
    }

    private static String describe(AgentDefinition agent) {
        return agent == null ? "默认流水线" : agent.name() + "(" + agent.id() + ")";
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

    /**
     * 图编排的节点状态默认按 Java 序列化克隆，阶段结果必须可序列化；
     * 手写编排下这两个 record 仅作为进程内值对象使用，不受影响。
     */
    record CalcTarget(String employeeId, String period) implements java.io.Serializable {
    }

    record StageResult(String text, List<CalcTarget> targets, long elapsedMs, boolean toolError)
            implements java.io.Serializable {
        StageResult(String text, List<CalcTarget> targets, long elapsedMs) {
            this(text, targets, elapsedMs, false);
        }
    }

    private record ResolvedFlow(
            FlowDefinition flow, Map<String, AgentDefinition> agents) {
    }
}
