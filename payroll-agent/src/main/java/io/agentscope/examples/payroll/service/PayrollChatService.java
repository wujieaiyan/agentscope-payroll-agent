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
import io.agentscope.examples.payroll.agent.ActionGuard;
import io.agentscope.examples.payroll.agent.KbChatIntent;
import io.agentscope.examples.payroll.agent.KbCitationCollector;
import io.agentscope.examples.payroll.agent.KbSearchBudget;
import io.agentscope.examples.payroll.agent.KbSearchScope;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.ToolSafety;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.examples.payroll.agent.ToolTraceRecorder;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.support.ToolTraceExtractor;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.TeamPlan;
import io.agentscope.examples.payroll.memory.AnswerCache;
import io.agentscope.examples.payroll.memory.SemanticCachePolicy;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.repository.ChatContextStore;
import io.agentscope.examples.payroll.router.AgentRouter;
import io.agentscope.examples.payroll.router.RouteDecision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;

/**
 * 管理台对话实现：固定编排「计算智能体 → 验证智能体 → 核算智能体」三段流水线。
 *
 * <p>编排放在确定性 Java 代码中而不是交给模型路由，保证每一轮工资核算都会经过独立验证，
 * 且阶段顺序、超时与降级行为可预测。单机管理台只维护一个会话，串行处理请求。
 */
public final class PayrollChatService implements PayrollChat {

    private static final Logger log = LoggerFactory.getLogger(PayrollChatService.class);
    private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(150);
    private static final int MAX_REPEATED_TOOL_CALLS = 3;
    private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CALCULATION_TOOLS =
            Set.of("calculate_payroll", "calculate_department_payroll", "generate_payslip");
    private static final int BATCH_VALIDATION_LIMIT = 3;
    private static final int LOG_PREVIEW_LIMIT = 400;
    /** 小模型会把“应加载技能/应调用工具”当成正文输出，甚至触发技能重试话术。 */
    private static final Pattern PSEUDO_TOOL_CALL = Pattern.compile(
            "(?is)(load_skill_through_path\\s*\\(|\"tool_calls\"\\s*:|<tool_call>|<function_call>"
                    + "|请求使用其他相关技能"
                    + "|由于[^\\n]{0,40}技能[^\\n]{0,20}(?:无法|不能)[^\\n]{0,20}(?:满足|完成))");

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
    private final ActiveTeamService activeTeam;
    private final boolean routingEnabled;
    private final boolean actionConfirmEnabled;
    private final int actionProposalTtlMinutes;
    private final Set<String> alwaysConfirmTools;
    private final ToolSafety toolSafety;
    private final AnswerCache answerCache;
    private final AgentRouter router;
    private final Object lock = new Object();
    private final ToolTraceRecorder traceRecorder = new ToolTraceRecorder();
    private final KbCitationCollector citationCollector = new KbCitationCollector();
    private final KbSearchBudget kbSearchBudget = new KbSearchBudget();
    private final List<ChatToolTrace> collectedTraces = new CopyOnWriteArrayList<>();
    private final List<PayrollChat.StageRun> stageRuns = new CopyOnWriteArrayList<>();
    private final RuntimeContext sessionContext;
    private volatile TraceScope runScope = TraceScope.none();
    private volatile ActionGuard roundGuard;
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
                () -> List.of(),
                ActiveTeamService.disabled());
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
            Supplier<List<AgentDefinition>> agentCatalogSupplier,
            ActiveTeamService activeTeam) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                activeAgentSupplier,
                activeFlowSupplier,
                activeFlowIdSupplier,
                agentCatalogSupplier,
                activeTeam,
                true);
    }

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
            Supplier<List<AgentDefinition>> agentCatalogSupplier,
            ActiveTeamService activeTeam,
            boolean routingEnabled) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                activeAgentSupplier,
                activeFlowSupplier,
                activeFlowIdSupplier,
                agentCatalogSupplier,
                activeTeam,
                routingEnabled,
                false,
                15,
                ToolSafety.defaults(),
                AnswerCache.disabled());
    }

    /**
     * @param actionConfirmEnabled 写工具是否需要会话内确认；关闭时按旧行为直接执行
     * @param actionProposalTtlMinutes 待确认提案的有效期
     * @param toolSafety 工具读写分类器；null 时使用默认分类
     * @param answerCache 语义/精确回答缓存；默认关闭，仅 AGENT 路径无工具调用时写入
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
            Supplier<List<AgentDefinition>> agentCatalogSupplier,
            ActiveTeamService activeTeam,
            boolean routingEnabled,
            boolean actionConfirmEnabled,
            int actionProposalTtlMinutes,
            ToolSafety toolSafety,
            AnswerCache answerCache) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                activeAgentSupplier,
                activeFlowSupplier,
                activeFlowIdSupplier,
                agentCatalogSupplier,
                activeTeam,
                routingEnabled,
                actionConfirmEnabled,
                actionProposalTtlMinutes,
                toolSafety,
                answerCache,
                AgentRouter.disabled());
    }

    /**
     * @param router 轻量模型路由；null 或未启用时沿用固定静态链路
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
            Supplier<List<AgentDefinition>> agentCatalogSupplier,
            ActiveTeamService activeTeam,
            boolean routingEnabled,
            boolean actionConfirmEnabled,
            int actionProposalTtlMinutes,
            ToolSafety toolSafety,
            AnswerCache answerCache,
            AgentRouter router) {
        this(
                engine,
                contextStore,
                sessionId,
                userId,
                graphOrchestration,
                memory,
                activeAgentSupplier,
                activeFlowSupplier,
                activeFlowIdSupplier,
                agentCatalogSupplier,
                activeTeam,
                routingEnabled,
                actionConfirmEnabled,
                actionProposalTtlMinutes,
                toolSafety,
                answerCache,
                router,
                null);
    }

    /**
     * @param alwaysConfirmTools 无论全局开关如何都必须确认的写工具；null 时使用默认知识库写工具
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
            Supplier<List<AgentDefinition>> agentCatalogSupplier,
            ActiveTeamService activeTeam,
            boolean routingEnabled,
            boolean actionConfirmEnabled,
            int actionProposalTtlMinutes,
            ToolSafety toolSafety,
            AnswerCache answerCache,
            AgentRouter router,
            Set<String> alwaysConfirmTools) {
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
        this.activeTeam = activeTeam == null ? ActiveTeamService.disabled() : activeTeam;
        this.routingEnabled = routingEnabled;
        this.actionConfirmEnabled = actionConfirmEnabled;
        this.actionProposalTtlMinutes = Math.max(1, actionProposalTtlMinutes);
        this.alwaysConfirmTools = alwaysConfirmTools;
        this.toolSafety = toolSafety == null ? ToolSafety.defaults() : toolSafety;
        this.answerCache = answerCache == null ? AnswerCache.disabled() : answerCache;
        this.router = router == null ? AgentRouter.disabled() : router;
        this.sessionContext =
                RuntimeContext.builder()
                        .userId(String.valueOf(userId))
                        .sessionId(String.valueOf(sessionId))
                        .put(ToolTraceRecorder.class, traceRecorder)
                        .put(KbCitationCollector.class, citationCollector)
                        .put(KbSearchBudget.class, kbSearchBudget)
                        .build();
        this.context = loadContext(contextStore, sessionId);
    }

    /** 每轮重新读取会话绑定，保证顶部切换知识库后本轮立即生效。 */
    private KbSearchScope kbSearchScope() {
        if (sessionId <= 0 || contextStore == null) {
            return KbSearchScope.unbound();
        }
        try {
            return new KbSearchScope(contextStore.kbSeriesIds(sessionId));
        } catch (RuntimeException e) {
            log.warn(
                    "[知识库] 读取会话绑定失败：sessionId={}，原因={}",
                    sessionId,
                    rootMessage(e));
            return KbSearchScope.unbound();
        }
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
        listener.onCitations(reply.citations());
        listener.onDone(reply);
    }

    @Override
    public List<PayrollChat.StageRun> stageRuns() {
        return List.copyOf(stageRuns);
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
            String question = message.trim();
            long startedAt = System.currentTimeMillis();
            KbSearchScope kbScope = kbSearchScope();
            boolean kbBound = kbScope.bound();
            boolean kbQuestion = kbBound && KbChatIntent.matches(question);
            sessionContext.put(KbSearchScope.class, kbScope);
            Set<String> tools = Collections.synchronizedSet(new LinkedHashSet<>());
            collectedTraces.clear();
            stageRuns.clear();
            traceRecorder.clear();
            citationCollector.clear();
            // 团队执行在同一轮内嵌套调用 executeDynamicFlow，这里统一清零，保证整轮共享 3 次额度。
            kbSearchBudget.clear();
            log.info("[多智能体] 开始处理问题：{}", preview(question, 120));

            ResolvedFlow resolvedFlow = resolveActiveFlow();
            PayrollAgentFactory.Team current = team(resolvedFlow != null);
            String plannedPath =
                    resolvedFlow != null
                            ? ChatRouting.FLOW
                            : !current.pipeline()
                                    ? ChatRouting.AGENT
                                    : graphOrchestration ? ChatRouting.GRAPH : ChatRouting.PIPELINE;
            String routerNote = "";
            String routerSignature = "";
            boolean routerDecided = false;
            if (resolvedFlow == null) {
                RouteDecision route = route(question);
                if (route == null) {
                    // 路由未启用：保持既有静态链路
                } else if (!route.routed()) {
                    routerNote =
                            "智能体路由未生效（" + route.failureReason() + "），沿用默认链路。";
                } else {
                    routerDecided = true;
                    routerSignature = route.signature();
                    routerNote = route.note() + " ";
                    if (RouteDecision.TARGET_AGENT.equals(route.target())) {
                        current = routedTeam(route.agent());
                        plannedPath = ChatRouting.AGENT;
                    } else if (RouteDecision.TARGET_TEAM.equals(route.target())) {
                        plannedPath = ChatRouting.TEAM_ACTIVE;
                    } else {
                        current = routedTeam(null);
                        plannedPath =
                                graphOrchestration ? ChatRouting.GRAPH : ChatRouting.PIPELINE;
                    }
                    log.info(
                            "[智能体路由] 本轮目标：{}，生效链路={}，智能体={}",
                            route.target(),
                            plannedPath,
                            current.activeAgent() == null ? "" : current.activeAgent().id());
                }
            }
            String persona =
                    resolvedFlow != null
                            ? "flow:" + resolvedFlow.flow().id()
                            : current.activeAgent() == null
                                    ? "pipeline"
                                    : "agent:" + current.activeAgent().id();
            String previousPersona = applyPersona(persona, events);
            String switchNote =
                    previousPersona == null
                                    || previousPersona.isBlank()
                                    || previousPersona.equals(persona)
                            ? ""
                            : "本轮刚切换角色：" + previousPersona + " → " + persona + "。";
            log.info(
                    "[多智能体] 本轮生效角色：{}（{}）",
                    persona,
                    resolvedFlow != null
                            ? "流程：" + resolvedFlow.flow().name()
                            : current.pipeline()
                                    ? "内置三段流水线"
                                    : "单个智能体");

            String cacheScope =
                    ChatRouting.AGENT.equals(plannedPath) && answerCache.enabled()
                            ? "v1|"
                                    + persona
                                    + "|"
                                    + modelId()
                                    + "|"
                                    + SemanticCacheContext.scope()
                            : "";
            if (!cacheScope.isEmpty()) {
                Optional<AnswerCache.Hit> cached = answerCache.lookup(cacheScope, question);
                if (cached.isPresent()) {
                    String cachedReply = cached.get().answer();
                    events.onText(cachedReply);
                    context.append(question, cachedReply);
                    String label =
                            "semantic".equals(cached.get().type()) ? "语义缓存" : "精确缓存";
                    ChatRouting routing =
                            buildRouting(
                                    ChatRouting.AGENT,
                                    persona,
                                    "本轮命中"
                                            + label
                                            + "（相似度 "
                                            + String.format(Locale.ROOT, "%.3f", cached.get().score())
                                            + "），未调用模型。",
                                    null,
                                    null,
                                    "cache:" + cached.get().type(),
                                    List.of(),
                                    0L,
                                    startedAt,
                                    List.of());
                    logRouting(routing);
                    log.info(
                            "[语义缓存] 命中答复：persona={}，类型={}，耗时={}ms",
                            persona,
                            cached.get().type(),
                            System.currentTimeMillis() - startedAt);
                    return new Reply(
                            cachedReply,
                            List.of(),
                            modelId(),
                            List.of(),
                            null,
                            routing,
                            List.of(),
                            new PayrollChat.CacheInfo(cached.get().type(), cached.get().score()));
                }
            }
            ActionGuard guard =
                    new ActionGuard(
                            userId,
                            sessionId,
                            plannedPath,
                            actionConfirmEnabled,
                            actionProposalTtlMinutes,
                            toolSafety,
                            events,
                            alwaysConfirmTools);
            roundGuard = guard;
            sessionContext.put(ActionGuard.class, guard);
            try {
                prepareContext(context, events, current.summarizer());
                String memoryBlock = memory.recall(userId, sessionId, question);
                if (!memoryBlock.isBlank()) {
                    log.info("[向量] 已注入历史线索：{} 字", memoryBlock.length());
                }
                String calculatorInput = context.augment(question, memoryBlock);

                ActiveTeamService.Attempt teamAttempt = ActiveTeamService.Attempt.skipped();
                // 已绑定知识库且当前就是协同智能体时，制度类问题不再交给团队接管，避免丢失检索。
                boolean kbDirect =
                        kbQuestion
                                && resolvedFlow == null
                                && !current.pipeline()
                                && !ChatRouting.TEAM_ACTIVE.equals(plannedPath);
                boolean teamGate =
                        resolvedFlow == null
                                && activeTeam.enabled()
                                && !kbDirect
                                && (ChatRouting.TEAM_ACTIVE.equals(plannedPath)
                                        || (!routerDecided && current.pipeline()));
                if (teamGate) {
                    teamAttempt =
                            activeTeam.attempt(
                                    this,
                                    userId,
                                    sessionId,
                                    question,
                                    calculatorInput,
                                    events,
                                    kbBound);
                    if (teamAttempt.succeeded()) {
                        String teamReply = composeReply(teamAttempt.reply(), guard);
                        // 末端节点已实时流出正文时只补尾部差量，避免整段重复推送。
                        String teamRemainder =
                                answerRemainder(teamReply, teamAttempt.streamedText());
                        if (!teamRemainder.isEmpty()) {
                            events.onText(teamRemainder);
                        }
                        context.append(question, teamReply);
                        log.info(
                                "[动态团队] ACTIVE 答复：plan={}，template={}，智能体={}，工具={}，trace={}，耗时={}ms",
                                teamAttempt.planId(),
                                teamAttempt.templateId(),
                                teamAttempt.agentIds(),
                                teamAttempt.tools(),
                                teamAttempt.traces().size(),
                                System.currentTimeMillis() - startedAt);
                        ChatRouting routing =
                                buildRouting(
                                        ChatRouting.TEAM_ACTIVE,
                                        persona,
                                        switchNote
                                                + routerNote
                                                + "本轮由动态团队接管（计划 "
                                                + teamAttempt.planId()
                                                + "）。",
                                        null,
                                        null,
                                        "team|" + teamAttempt.templateId() + routerSuffix(routerSignature),
                                        teamAttempt.agentIds(),
                                        teamAttempt.runId(),
                                        startedAt,
                                        List.of());
                        logRouting(routing);
                        return new Reply(
                                teamReply,
                                teamAttempt.tools(),
                                modelId(),
                                teamAttempt.traces(),
                                teamAttempt.teamReply(),
                                routing,
                                guard.proposals(),
                                null,
                                teamAttempt.citations());
                    }
                    if (teamAttempt.recorded()) {
                        clearRoundState();
                    }
                }

                String fromPath = null;
                String fallbackReason = null;
                if (teamAttempt.recorded()) {
                    fromPath = ChatRouting.TEAM_ACTIVE;
                    fallbackReason = teamAttempt.fallbackReason();
                }

                String reply;
                String path;
                String reason;
                String signature;
                List<String> agentIds;
                if (resolvedFlow != null) {
                    reply = flowReply(calculatorInput, resolvedFlow, events, tools);
                    path = ChatRouting.FLOW;
                    reason =
                            "本轮由默认流程「"
                                    + resolvedFlow.flow().name()
                                    + "」编排（"
                                    + resolvedFlow.flow().nodes().size()
                                    + " 个节点）。";
                    signature =
                            "flow:"
                                    + resolvedFlow.flow().id()
                                    + "|"
                                    + resolvedFlow.flow().nodes().size();
                    agentIds = flowAgentIds(resolvedFlow);
                } else if (!current.pipeline()) {
                    reply = activeAgentReply(calculatorInput, current, events, tools);
                    path = ChatRouting.AGENT;
                    AgentDefinition agent = current.activeAgent();
                    reason =
                            routerNote
                                    + "本轮由智能体「"
                                    + (agent == null ? "核算智能体" : agent.name())
                                    + "」直接作答。";
                    signature =
                            PayrollAgentFactory.definitionSignature(agent)
                                    + routerSuffix(routerSignature);
                    agentIds = agent == null ? List.of() : List.of(agent.id());
                } else if (graphOrchestration) {
                    reply = graphReply(question, calculatorInput, current, events, tools);
                    path = ChatRouting.GRAPH;
                    reason = routerNote + "本轮由图编排（langgraph4j）驱动三阶段执行。";
                    signature = "graph" + routerSuffix(routerSignature);
                    agentIds = roundAgentIds();
                } else {
                    reply = legacyReply(question, calculatorInput, current, events, tools);
                    path = ChatRouting.PIPELINE;
                    reason = routerNote + "本轮使用内置三段流水线（计算 → 验证 → 核算）。";
                    signature = "pipeline" + routerSuffix(routerSignature);
                    agentIds = roundAgentIds();
                }
                if (fromPath != null) {
                    reason = reason + " 动态团队接管失败，已回退静态链路。";
                }
                reply = composeReply(reply, guard);
                writeAnswerCache(cacheScope, question, path, tools, guard, reply);
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
                ChatRouting routing =
                        buildRouting(
                                path,
                                persona,
                                switchNote + reason,
                                fromPath,
                                fallbackReason,
                                signature,
                                agentIds,
                                0L,
                                startedAt,
                                stageKeys());
                logRouting(routing);
                return new Reply(
                        reply,
                        List.copyOf(tools),
                        modelId(),
                        List.copyOf(collectedTraces),
                        teamAttempt.teamReply(),
                        routing,
                        guard.proposals(),
                        null,
                        citationCollector.citations());
            } finally {
                roundGuard = null;
                sessionContext.put(ActionGuard.class, null);
            }
        }
    }

    /**
     * 只缓存稳定、只读的单智能体问答：无工具调用、无待确认提案、答复长度受限。
     * 流水线、流程与动态团队的编排结果不进入缓存。
     */
    private void writeAnswerCache(
            String scope,
            String question,
            String path,
            Set<String> tools,
            ActionGuard guard,
            String reply) {
        if (scope == null
                || scope.isEmpty()
                || !SemanticCachePolicy.cacheable(
                        path, tools, guard != null && guard.hasProposal(), reply)) {
            return;
        }
        answerCache.store(scope, question, reply);
        log.info("[语义缓存] 已缓存答复：persona 作用域={}，长度={}", scope, reply.length());
    }

    /**
     * 提案后由服务端收尾：保留提案前已流式输出的正文，模型在卡片之后的续写一律丢弃，
     * 避免出现「已执行完成」这类与实际状态不符的说法。
     */
    /**
     * 计算还需补推到回答流的尾部文本。
     *
     * <p>末端节点已实时流出前缀时只发差量，完全一致时不重复发送；若历史推流与最终
     * 答复对不上（例如条件分支改由其他节点作答），退回整段发送，保证内容正确。
     */
    private static String answerRemainder(String reply, String streamed) {
        String full = reply == null ? "" : reply.trim();
        String shown = streamed == null ? "" : streamed.trim();
        if (shown.isEmpty()) {
            return full;
        }
        if (full.equals(shown)) {
            return "";
        }
        if (full.startsWith(shown)) {
            return full.substring(shown.length());
        }
        return full;
    }

    private static String composeReply(String modelText, ActionGuard guard) {
        if (guard == null || !guard.hasProposal()) {
            return modelText == null ? "" : modelText;
        }
        String before = guard.textBeforeProposal();
        String template = guard.finalReplyTemplate();
        return before.isBlank() ? template : before + "\n\n" + template;
    }

    private ChatRouting buildRouting(
            String path,
            String persona,
            String reason,
            String fromPath,
            String fallbackReason,
            String signature,
            List<String> agentIds,
            long teamRunId,
            long startedAt,
            List<String> stages) {
        if (!routingEnabled) {
            return null;
        }
        return new ChatRouting(
                path,
                persona,
                reason,
                fromPath,
                fallbackReason,
                modelId(),
                signature,
                agentIds,
                teamRunId,
                System.currentTimeMillis() - startedAt,
                stages);
    }

    private static void logRouting(ChatRouting routing) {
        if (routing == null) {
            return;
        }
        log.info(
                "[路由] path={}，persona={}，agents={}，teamRunId={}，from={}，fallback={}，stages={}，耗时={}ms",
                routing.path(),
                routing.persona(),
                routing.agentIds(),
                routing.teamRunId(),
                routing.fromPath(),
                routing.fallbackReason(),
                routing.stages(),
                routing.elapsedMs());
    }

    private static List<String> flowAgentIds(ResolvedFlow resolved) {
        return resolved.flow().nodes().stream()
                .filter(node -> FlowNode.TYPE_AGENT.equalsIgnoreCase(node.type()))
                .map(FlowNode::agentId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    /** 本轮实际参与阶段的智能体（流水线跳过阶段也会记入，按 stageRuns 去重）。 */
    private List<String> roundAgentIds() {
        return stageRuns.stream()
                .map(PayrollChat.StageRun::agentId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private List<String> stageKeys() {
        return stageRuns.stream()
                .map(PayrollChat.StageRun::stageKey)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
    }

    /** 动态尝试失败后清空本轮缓冲，避免半套动态阶段混入静态运行的追溯。 */
    private void clearRoundState() {
        collectedTraces.clear();
        stageRuns.clear();
        traceRecorder.clear();
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

        if (roundGuard != null && roundGuard.hasProposal()) {
            log.info("[多智能体] 已生成待确认操作，跳过后续校验与核算阶段");
            events.onPhase("已生成待确认操作，本轮停止后续核算");
            recordSkippedStage("validator", 0);
            recordSkippedStage("accountant", 0);
            return calculation.text();
        }

        String validation;
        if (calculation.targets().isEmpty()) {
            validation = "本轮未执行工资计算，无需独立校验。";
            events.onAgentStart("validator", "② 验证智能体：本轮无需校验");
            events.onAgentOutput("validator", validation);
            events.onAgentEnd("validator");
            recordSkippedStage("validator", validation.length());
            log.info("[多智能体] ② 跳过验证：本轮没有工资计算对象");
        } else {
            validation = validate(current.validator(), question, calculation, events, tools);
            log.info("[多智能体] ② 验证完成：输出={}", preview(validation, LOG_PREVIEW_LIMIT));
        }

        if (calculation.targets().isEmpty()) {
            // 查询类问题（员工清单/档案/政策/考勤）直接返回计算记录，避免小模型改写出错误的工资结论。
            String reply = calculation.text().trim();
            recordSkippedStage("accountant", reply.length());
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
        List<String> errors = FlowValidator.validate(flow, agents);
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
    String applyPersona(String persona, ChatStreamListener events) {
        if (sessionId <= 0 || persona == null || persona.isBlank()) {
            return null;
        }
        String previous;
        try {
            previous = contextStore.persona(sessionId);
        } catch (RuntimeException e) {
            log.warn("[会话] 读取角色绑定失败：sessionId={}，原因={}", sessionId, rootMessage(e));
            return null;
        }
        if (persona.equals(previous)) {
            return null;
        }
        if (previous != null && !previous.isBlank()) {
            context = new ConversationContext("", 0);
            persistContext(context);
            memory.onPersonaChanged(userId, sessionId);
            try {
                int canceled = contextStore.cancelPendingActions(sessionId);
                if (canceled > 0) {
                    log.info("[会话] 角色切换作废未确认提案：sessionId={}，数量={}", sessionId, canceled);
                    events.onPhase("检测到智能体切换，已作废未确认的写操作");
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[会话] 作废未确认提案失败：sessionId={}，原因={}",
                        sessionId,
                        rootMessage(e));
            }
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
        return previous;
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
                            (node, input, listener, answerStream) ->
                                    executeFlowNode(
                                            node,
                                            resolved.agents().get(node.agentId()),
                                            input,
                                            listener,
                                            tools,
                                            answerStream));
        } catch (RuntimeException e) {
            throw chatFailure(e);
        }
        String reply = outcome.reply().trim();
        if (reply.isBlank()) {
            String nodes = outcome.nodeResults().entrySet().stream()
                    .filter(entry -> !entry.getValue().text().isBlank())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining("、"));
            log.error(
                    "[流程编排] 流程 {} 未产生答复：执行节点={}，有输出节点={}",
                    resolved.flow().id(),
                    outcome.nodeResults().keySet(),
                    nodes);
            throw new PayrollException(
                    "流程「" + resolved.flow().name() + "」执行完成但没有产生答复内容"
                            + (nodes.isBlank() ? "" : "（有输出的节点：" + nodes + "）")
                            + "，请检查智能体节点输出或模型服务");
        }
        // 末端智能体已实时流出正文时只补尾部差量，避免整段重复推送。
        String remainder = answerRemainder(reply, outcome.streamedText());
        if (!remainder.isEmpty()) {
            events.onText(remainder);
        }
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
            Set<String> allTools,
            boolean answerStream) {
        return executeFlowNode(
                node, agent, input, listener, allTools, roleOf(node.id()), answerStream);
    }

    private AgentFlowExecutor.NodeResult executeFlowNode(
            FlowNode node,
            AgentDefinition agent,
            String input,
            ChatStreamListener listener,
            Set<String> allTools,
            String role,
            boolean answerStream) {
        if (agent == null) {
            throw new PayrollException("流程节点引用的智能体不存在：" + node.agentId());
        }
        String label = node.label();
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        boolean kbSearch = PayrollAgentFactory.kbSearchNode(role, agent);
        KbSearchScope kbScope = sessionContext.get(KbSearchScope.class);
        RuntimeContext.Builder nodeContextBuilder =
                RuntimeContext.builder()
                        .userId(String.valueOf(userId))
                        .sessionId(sessionId + ":" + node.id())
                        .put(ToolTraceRecorder.class, recorder)
                        .put(KbCitationCollector.class, citationCollector)
                        .put(KbSearchBudget.class, kbSearchBudget)
                        .put(
                                TraceScope.class,
                                new TraceScope(
                                        runScope.runId(),
                                        runScope.planId(),
                                        node.id(),
                                        node.agentId(),
                                        role,
                                        runScope.mode(),
                                        node.id()));
        if (kbScope != null) {
            nodeContextBuilder.put(KbSearchScope.class, kbScope);
        }
        ActionGuard guard = roundGuard;
        if (guard != null) {
            nodeContextBuilder.put(ActionGuard.class, guard);
        }
        RuntimeContext nodeContext = nodeContextBuilder.build();
        PayrollAgentFactory.NodeAgent runtime =
                PayrollAgentFactory.sharedFlowNodeAgent(engine, agent, kbSearch);
        try {
            StageResult result =
                    runStage(
                            node.id(),
                            label,
                            runtime.agent(),
                            input,
                            listener,
                            answerStream,
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
        sessionContext.put(TraceScope.class, stageScope(stageKey));
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

    /** 本轮团队运行坐标；P0-3 在创建静态运行后注入，未启用时保持 none。 */
    public void useRunScope(TraceScope scope) {
        this.runScope = scope == null ? TraceScope.none() : scope;
    }

    /**
     * 影子模式执行动态计划：复用现有 DAG 执行器与节点级上下文，但不写会话上下文、
     * 不向用户推送事件，返回阶段与工具追溯供落库。
     *
     * <p>调用方必须为本次运行使用独立的服务实例，避免与用户主链路共享阶段列表。
     */
    public DynamicFlowResult executeDynamicFlow(
            TeamPlan plan,
            FlowDefinition flow,
            Map<String, AgentDefinition> agents,
            String input,
            TraceScope scope) {
        return executeDynamicFlow(plan, flow, agents, input, scope, ChatStreamListener.NOOP);
    }

    /** 同 {@link #executeDynamicFlow}，但把节点进度与文本转发给 ACTIVE 模式的 SSE 监听器。 */
    public DynamicFlowResult executeDynamicFlow(
            TeamPlan plan,
            FlowDefinition flow,
            Map<String, AgentDefinition> agents,
            String input,
            TraceScope scope,
            ChatStreamListener listener) {
        if (flow == null) {
            throw new PayrollException("动态流程不能为空");
        }
        synchronized (lock) {
            collectedTraces.clear();
            stageRuns.clear();
            traceRecorder.clear();
            citationCollector.clear();
            TraceScope previous = runScope;
            useRunScope(scope);
            Set<String> tools = Collections.synchronizedSet(new LinkedHashSet<>());
            Map<String, String> roles = new LinkedHashMap<>();
            if (plan != null) {
                for (TeamPlan.PlanNode node : plan.agentNodes()) {
                    roles.put(node.id(), node.role());
                }
            }
            try {
                AgentFlowExecutor.Outcome outcome =
                        AgentFlowExecutor.execute(
                                flow,
                                input,
                                listener == null ? ChatStreamListener.NOOP : listener,
                                (node, nodeInput, nodeListener, answerStream) ->
                                        executeFlowNode(
                                                node,
                                                agents.get(node.agentId()),
                                                nodeInput,
                                                nodeListener,
                                                tools,
                                                roles.getOrDefault(node.id(), roleOf(node.id())),
                                                answerStream));
                return new DynamicFlowResult(
                        outcome.reply() == null ? "" : outcome.reply().trim(),
                        outcome.targets(),
                        List.copyOf(stageRuns),
                        List.copyOf(collectedTraces),
                        List.copyOf(tools),
                        outcome.nodeResults(),
                        outcome.streamedText(),
                        citationCollector.citations());
            } finally {
                useRunScope(previous);
            }
        }
    }

    private TraceScope stageScope(String stageKey) {
        return new TraceScope(
                runScope.runId(),
                runScope.planId(),
                stageKey,
                builtInAgentId(stageKey),
                roleOf(stageKey),
                runScope.mode(),
                stageKey);
    }

    private static String roleOf(String stageKey) {
        if (stageKey == null) {
            return "AGENT";
        }
        return switch (stageKey) {
            case "calculator" -> "CALCULATOR";
            case "validator" -> "VALIDATOR";
            case "accountant" -> "ACCOUNTANT";
            default -> stageKey.toUpperCase(Locale.ROOT);
        };
    }

    private static String builtInAgentId(String stageKey) {
        return switch (stageKey == null ? "" : stageKey) {
            case "calculator" -> "PayrollCalculatorAgent";
            case "validator" -> "PayrollValidatorAgent";
            case "accountant" -> "PayrollAccountantAgent";
            default -> stageKey;
        };
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
        int[] toolCalls = {0};
        boolean[] toolError = {false};
        String[] lastToolSignature = {null};
        int[] repeatedToolCalls = {0};

        try {
            agent.streamEvents(new UserMessage(input), callContext)
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolCallStartEvent call) {
                                    toolCalls[0]++;
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
                                    String signature = result.getToolCallName() + "|" + args;
                                    if (signature.equals(lastToolSignature[0])) {
                                        repeatedToolCalls[0]++;
                                    } else {
                                        lastToolSignature[0] = signature;
                                        repeatedToolCalls[0] = 1;
                                    }
                                    if (repeatedToolCalls[0] >= MAX_REPEATED_TOOL_CALLS) {
                                        log.error(
                                                "[多智能体][{}] 检测到重复工具调用，中止阶段：{}，参数={}",
                                                stageKey,
                                                result.getToolCallName(),
                                                preview(args, 200));
                                        throw new PayrollException(
                                                "智能体连续 "
                                                        + repeatedToolCalls[0]
                                                        + " 次以相同参数调用 "
                                                        + result.getToolCallName()
                                                        + "，已中止本轮；请重试或更换模型");
                                    }
                                    collectTargets(result.getToolCallName(), args, targets);
                                    if (!"SUCCESS".equals(result.getState().name())) {
                                        toolError[0] = true;
                                    }
                                    ChatToolTrace trace =
                                            traceOf(
                                                    recorder,
                                                    stageKey,
                                                    result,
                                                    args,
                                                    callContext);
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
                                        ActionGuard guard =
                                                callContext.get(ActionGuard.class);
                                        // 提案后的模型续写不展示，终稿由服务端组装
                                        if (guard == null || !guard.hasProposal()) {
                                            if (guard != null) {
                                                guard.observeText(delta.getDelta());
                                            }
                                            listener.onText(delta.getDelta());
                                        }
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
                    .timeout(STAGE_TIMEOUT)
                    .blockLast();
        } catch (RuntimeException e) {
            boolean timeout = Exceptions.unwrap(e) instanceof TimeoutException;
            recordStage(
                    stageKey,
                    timeout ? AgentRunRecord.STATUS_TIMEOUT : AgentRunRecord.STATUS_FAILED,
                    toolCalls[0],
                    toolError[0] ? 1 : 0,
                    validationPassed(stageKey, toolError[0]),
                    streamed.length(),
                    System.currentTimeMillis() - startedAt,
                    timeout
                            ? "阶段超过 " + STAGE_TIMEOUT.toSeconds() + " 秒未完成"
                            : rootMessage(e),
                    callContext);
            if (timeout) {
                throw new PayrollException(
                        "智能体阶段超过 "
                                + STAGE_TIMEOUT.toSeconds()
                                + " 秒未完成，已中止；模型可能陷入重复生成或工具循环，请重试或更换模型",
                        e);
            }
            throw e;
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
            ActionGuard guard = callContext.get(ActionGuard.class);
            if (answerStage) {
                if (guard == null || !guard.hasProposal()) {
                    listener.onText(text);
                }
            } else {
                listener.onAgentOutput(stageKey, text);
            }
        }
        log.debug("[多智能体][{}] 输出：\n{}", stageKey, text);
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (text.isBlank()) {
            log.error(
                    "[多智能体][{}] 模型未返回文本：工具调用={}，流式字数={}，最终事件文本={}",
                    stageKey,
                    toolCalls[0],
                    streamed.length(),
                    finalText[0] == null ? "null" : finalText[0].length() + "字");
        }
        boolean pseudoToolCall = toolCalls[0] == 0 && PSEUDO_TOOL_CALL.matcher(text).find();
        if (pseudoToolCall) {
            log.error(
                    "[多智能体][{}] 输出疑似伪工具调用/技能重试文本：{}",
                    stageKey,
                    preview(text, 300));
        }
        String stageError =
                text.isBlank()
                        ? "模型未返回文本（工具调用 " + toolCalls[0] + " 次）"
                        : pseudoToolCall ? "输出疑似伪工具调用或技能重试文本，已拦截" : null;
        recordStage(
                stageKey,
                text.isBlank() ? AgentRunRecord.STATUS_EMPTY : AgentRunRecord.STATUS_SUCCESS,
                toolCalls[0],
                toolError[0] ? 1 : 0,
                validationPassed(stageKey, toolError[0]),
                text.length(),
                elapsedMs,
                stageError,
                callContext);
        if (pseudoToolCall) {
            throw new PayrollException(
                    "智能体「" + stageLabel + "」返回了工具或技能调用说明而不是答复；角色技能已由系统预加载，"
                            + "无需再次加载。请重试，或改用更大的模型（当前 " + modelId() + "）");
        }
        return new StageResult(text, List.copyOf(targets), elapsedMs, toolError[0]);
    }

    private void recordStage(
            String stageKey,
            String status,
            int toolCalls,
            int toolErrors,
            Boolean validationPassed,
            int outputChars,
            long durationMs,
            String errorMessage,
            RuntimeContext callContext) {
        TraceScope scope = callContext == null ? null : callContext.get(TraceScope.class);
        String nodeId =
                scope != null && scope.nodeId() != null && !scope.nodeId().isBlank()
                        ? scope.nodeId()
                        : stageKey;
        String agentId =
                scope != null && scope.agentId() != null && !scope.agentId().isBlank()
                        ? scope.agentId()
                        : builtInAgentId(stageKey);
        String role =
                scope != null && scope.role() != null && !scope.role().isBlank()
                        ? scope.role()
                        : roleOf(stageKey);
        stageRuns.add(
                new PayrollChat.StageRun(
                        nodeId,
                        stageKey,
                        agentId,
                        role,
                        status,
                        toolCalls,
                        toolErrors,
                        validationPassed,
                        outputChars,
                        Math.max(0L, durationMs),
                        errorMessage));
    }

    private void recordSkippedStage(String stageKey, int outputChars) {
        stageRuns.add(
                new PayrollChat.StageRun(
                        stageKey,
                        stageKey,
                        builtInAgentId(stageKey),
                        roleOf(stageKey),
                        AgentRunRecord.STATUS_SKIPPED,
                        0,
                        0,
                        null,
                        Math.max(0, outputChars),
                        0L,
                        null));
    }

    private static Boolean validationPassed(String stageKey, boolean toolError) {
        return "validator".equals(stageKey) ? !toolError : null;
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
            ToolTraceRecorder recorder,
            String stageKey,
            ToolResultEndEvent result,
            String args,
            RuntimeContext callContext) {
        TraceScope scope = callContext == null ? null : callContext.get(TraceScope.class);
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
                            entry.amounts())
                    .withScope(scope);
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
                        evidence.amounts())
                .withScope(scope);
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
            stageRuns.clear();
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
     *
     * @param flowMode 流程生效时不参与核算智能体配置，避免已取消的智能体干扰流程链路
     */
    private static String routerSuffix(String signature) {
        return signature == null || signature.isBlank() ? "" : "|" + signature;
    }

    /** 纯模型路由；目录读取或调用失败时折成回退决策，不打断对话。 */
    private RouteDecision route(String question) {
        if (!router.enabled()) {
            return null;
        }
        List<AgentDefinition> candidates;
        try {
            candidates =
                    AgentRouter.candidates(
                            agentCatalogSupplier == null
                                    ? List.of()
                                    : agentCatalogSupplier.get());
        } catch (RuntimeException e) {
            log.warn("[智能体路由] 读取智能体目录失败，沿用默认链路：{}", rootMessage(e));
            return RouteDecision.fallback("读取智能体目录失败：" + rootMessage(e));
        }
        boolean teamAvailable;
        try {
            teamAvailable = activeTeam.allowed(userId, sessionId);
        } catch (RuntimeException e) {
            log.warn("[智能体路由] 判断团队可用性失败，按不可用处理：{}", rootMessage(e));
            teamAvailable = false;
        }
        try {
            return router.decide(question, candidates, teamAvailable, sessionId + ":" + userId);
        } catch (RuntimeException e) {
            log.warn("[智能体路由] 决策异常，沿用默认链路：{}", rootMessage(e));
            return RouteDecision.fallback(rootMessage(e));
        }
    }

    /** 加载路由目标对应的智能体实例；null 表示内置三段流水线。 */
    private PayrollAgentFactory.Team routedTeam(AgentDefinition agent) {
        PayrollAgentFactory.Team routed = PayrollAgentFactory.sharedTeam(engine, agent);
        if (team != routed) {
            log.info(
                    "[智能体路由] 已加载路由目标：{}，缓存条目={}",
                    agent == null ? "内置三段流水线" : agent.name() + "（" + agent.id() + "）",
                    PayrollAgentFactory.cachedAgentCount());
            team = routed;
        }
        return routed;
    }

    private PayrollAgentFactory.Team team(boolean flowMode) {
        AgentDefinition active = flowMode ? null : resolveActiveAgent();
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
        } catch (PayrollException e) {
            // 配置了智能体但不可用时必须失败，不能静默回退到薪酬流水线。
            throw e;
        } catch (RuntimeException e) {
            // 与 resolveActiveFlow 对齐：未知异常视为配置/程序故障，直接报错而非更换回答角色。
            throw new PayrollException(
                    "读取核算智能体配置失败，已阻止回退薪酬流水线：" + rootMessage(e), e);
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
    public record CalcTarget(String employeeId, String period) implements java.io.Serializable {
    }

    /** 动态流程执行结果：只含运行期数据，由调用方决定是否落库。 */
    public record DynamicFlowResult(
            String reply,
            List<CalcTarget> targets,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces,
            List<String> tools,
            Map<String, AgentFlowExecutor.NodeResult> nodeResults,
            String streamedText,
            List<Citation> citations) {

        public DynamicFlowResult {
            targets = targets == null ? List.of() : List.copyOf(targets);
            stages = stages == null ? List.of() : List.copyOf(stages);
            traces = traces == null ? List.of() : List.copyOf(traces);
            tools = tools == null ? List.of() : List.copyOf(tools);
            nodeResults = nodeResults == null ? Map.of() : Map.copyOf(nodeResults);
            streamedText = streamedText == null ? "" : streamedText;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public DynamicFlowResult(
                String reply,
                List<CalcTarget> targets,
                List<PayrollChat.StageRun> stages,
                List<ChatToolTrace> traces,
                List<String> tools,
                Map<String, AgentFlowExecutor.NodeResult> nodeResults,
                String streamedText) {
            this(reply, targets, stages, traces, tools, nodeResults, streamedText, List.of());
        }

        public DynamicFlowResult(
                String reply,
                List<CalcTarget> targets,
                List<PayrollChat.StageRun> stages,
                List<ChatToolTrace> traces,
                List<String> tools,
                Map<String, AgentFlowExecutor.NodeResult> nodeResults) {
            this(reply, targets, stages, traces, tools, nodeResults, "", List.of());
        }
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
