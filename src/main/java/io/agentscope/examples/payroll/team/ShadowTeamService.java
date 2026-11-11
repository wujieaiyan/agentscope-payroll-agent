package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SHADOW 模式旁路：主链路返回后按采样率异步生成计划并执行，只记录不展示。
 *
 * <p>最多额外两次轻量模型调用（意图 + 计划）与一次 DAG 执行；任何失败都落一条
 * {@code is_primary=false}、{@code mode=SHADOW} 的运行，并写明 L0-L4 回退原因。
 */
public final class ShadowTeamService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShadowTeamService.class);
    private static final int REASON_LIMIT = 256;
    private static final long EXECUTION_TIMEOUT_SECONDS = 300L;

    private final TeamSettings settings;
    private final H2AgentTeamStore teamStore;
    private final H2ChatStore chatStore;
    private final PayrollChatFactory chatFactory;
    private final TeamPlanPipeline pipeline;
    private final Random random;
    private final ExecutorService planner;
    private final ExecutorService flowExecutor;
    private final boolean enabled;

    public ShadowTeamService(
            TeamSettings settings,
            H2AgentTeamStore teamStore,
            H2ChatStore chatStore,
            AgentStatsService statsService,
            Supplier<List<AgentDefinition>> catalogSupplier,
            PayrollChatFactory chatFactory) {
        this(
                settings,
                teamStore,
                chatStore,
                statsService,
                catalogSupplier,
                chatFactory,
                new TeamLlmClient(),
                new Random());
    }

    public ShadowTeamService(
            TeamSettings settings,
            H2AgentTeamStore teamStore,
            H2ChatStore chatStore,
            AgentStatsService statsService,
            Supplier<List<AgentDefinition>> catalogSupplier,
            PayrollChatFactory chatFactory,
            TeamLlmClient llm,
            Random random) {
        this.settings = settings == null ? new TeamSettings(TeamMode.OFF, 0, 0, 0D) : settings;
        this.teamStore = teamStore;
        this.chatStore = chatStore;
        this.chatFactory = chatFactory;
        this.pipeline =
                new TeamPlanPipeline(
                        this.settings,
                        catalogSupplier,
                        statsService,
                        llm == null ? TeamLlmClient.disabled() : llm);
        this.random = random == null ? new Random() : random;
        this.enabled =
                this.settings.mode() == TeamMode.SHADOW
                        && teamStore != null
                        && chatStore != null
                        && chatFactory != null
                        && this.settings.shadowSampleRate() > 0D;
        if (enabled) {
            this.planner =
                    Executors.newSingleThreadExecutor(
                            runnable -> daemon(runnable, "team-shadow-planner"));
            this.flowExecutor =
                    Executors.newFixedThreadPool(
                            2, runnable -> daemon(runnable, "team-shadow-flow"));
        } else {
            this.planner = null;
            this.flowExecutor = null;
        }
    }

    public static ShadowTeamService disabled() {
        return new ShadowTeamService(
                new TeamSettings(TeamMode.OFF, 0, 0, 0D),
                null,
                null,
                null,
                List::of,
                null,
                TeamLlmClient.disabled(),
                null);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 主链路完成后调用；按采样率决定是否旁路执行，绝不向调用方抛异常。 */
    public void trigger(
            long userId,
            long sessionId,
            long messageId,
            String question,
            Long baselineRunId) {
        if (!enabled || question == null || question.isBlank()) {
            return;
        }
        double sample = random.nextDouble();
        if (sample >= settings.shadowSampleRate()) {
            log.debug(
                    "[动态团队] 影子未采样：session={}，message={}，sample={}，rate={}",
                    sessionId,
                    messageId,
                    sample,
                    settings.shadowSampleRate());
            return;
        }
        try {
            planner.execute(
                    () -> {
                        try {
                            runNow(userId, sessionId, messageId, question, baselineRunId);
                        } catch (RuntimeException e) {
                            log.warn(
                                    "[动态团队] 影子运行异常，已忽略：session={}，message={}，原因={}",
                                    sessionId,
                                    messageId,
                                    rootMessage(e));
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("[动态团队] 提交影子任务失败：{}", rootMessage(e));
        }
    }

    /** 同步执行一次影子编排，供默认异步入口与测试复用。 */
    public ShadowOutcome runNow(
            long userId,
            long sessionId,
            long messageId,
            String question,
            Long baselineRunId) {
        if (!enabled) {
            return ShadowOutcome.skipped("MODE_NOT_SHADOW");
        }
        long started = System.currentTimeMillis();
        String planId = "shadow-" + sessionId + "-" + messageId + "-" + System.nanoTime();
        TeamPlanPipeline.Attempt attempt = pipeline.prepare(question, planId);
        if (attempt instanceof TeamPlanPipeline.Fallback fallback) {
            log.info(
                    "[动态团队] 影子计划回退：session={}，message={}，原因={}",
                    sessionId,
                    messageId,
                    fallback.reason());
            long runId =
                    recordFallback(
                            userId,
                            sessionId,
                            messageId,
                            baselineRunId,
                            planId,
                            fallback.intent() == null ? null : fallback.intent().taskType(),
                            fallback.templateId(),
                            "{}",
                            fallback.catalogJson(),
                            fallback.scoreJson(),
                            fallback.reason(),
                            started);
            return new ShadowOutcome(
                    runId, planId, AgentTeamRun.STATUS_FALLBACK, fallback.reason(), false);
        }
        TeamPlanPipeline.Prepared prepared = (TeamPlanPipeline.Prepared) attempt;
        TeamIntent intent = prepared.intent();
        TeamTemplate template = prepared.template();
        TeamPlan plan = prepared.plan();
        FlowDefinition flow = prepared.flow();
        Map<String, AgentDefinition> agents = prepared.agents();
        long runId;
        try {
            AgentTeamRun run =
                    new AgentTeamRun(
                            0L,
                            planId,
                            sessionId,
                            messageId,
                            userId,
                            AgentTeamRun.MODE_SHADOW,
                            intent.taskType(),
                            template.id(),
                            JsonSupport.toJson(plan),
                            prepared.catalogJson(),
                            prepared.scoreJson(),
                            AgentTeamRun.STATUS_RUNNING,
                            null,
                            baselineRunId,
                            null,
                            null,
                            false,
                            0L,
                            LocalDateTime.now(),
                            null,
                            null,
                            null);
            runId = teamStore.createRun(run);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 创建影子运行失败，忽略本轮：{}", rootMessage(e));
            return ShadowOutcome.skipped("RUN_CREATE_FAILED");
        }
        TraceScope scope =
                new TraceScope(
                        runId, planId, null, null, null, AgentTeamRun.MODE_SHADOW, null);
        PayrollChat chat = null;
        long execStarted = System.currentTimeMillis();
        try {
            chat = chatFactory.create(sessionId, userId);
            if (!(chat instanceof PayrollChatService service)) {
                finishFallback(runId, "L3: 对话实现不支持动态流程", execStarted);
                return new ShadowOutcome(
                        runId,
                        planId,
                        AgentTeamRun.STATUS_FALLBACK,
                        "L3: 对话实现不支持动态流程",
                        true);
            }
            PayrollChatService dynamicChat = service;
            Future<PayrollChatService.DynamicFlowResult> future =
                    flowExecutor.submit(
                            () ->
                                    dynamicChat.executeDynamicFlow(
                                            plan, flow, agents, question, scope));
            PayrollChatService.DynamicFlowResult result =
                    future.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ChatToolTrace> traces = persistTraces(sessionId, runId, result.traces());
            persistStages(runId, result.stages(), traces);
            String reply = result.reply() == null ? "" : result.reply().trim();
            String reason = reply.isEmpty() ? "L3: 动态流程未产生答复" : null;
            String status =
                    reason == null ? AgentTeamRun.STATUS_SUCCESS : AgentTeamRun.STATUS_FALLBACK;
            teamStore.finishRun(
                    runId, status, reason, System.currentTimeMillis() - execStarted);
            log.info(
                    "[动态团队] 影子执行完成：runId={}，plan={}，template={}，status={}，节点={}，trace={}，耗时={}ms",
                    runId,
                    planId,
                    template.id(),
                    status,
                    result.stages().size(),
                    traces.size(),
                    System.currentTimeMillis() - started);
            return new ShadowOutcome(runId, planId, status, reason, true);
        } catch (TimeoutException e) {
            String reason = "L3: 动态流程超过 " + EXECUTION_TIMEOUT_SECONDS + " 秒";
            finishFallback(runId, reason, execStarted);
            log.warn("[动态团队] 影子执行超时：runId={}，plan={}", runId, planId);
            return new ShadowOutcome(
                    runId, planId, AgentTeamRun.STATUS_FALLBACK, reason, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String reason = "L3: 影子执行被中断";
            finishFallback(runId, reason, execStarted);
            return new ShadowOutcome(
                    runId, planId, AgentTeamRun.STATUS_FALLBACK, reason, true);
        } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
            String reason = "L3: 动态流程执行失败：" + rootMessage(e);
            finishFallback(runId, reason, execStarted);
            log.warn(
                    "[动态团队] 影子执行失败：runId={}，plan={}，原因={}",
                    runId,
                    planId,
                    rootMessage(e));
            return new ShadowOutcome(
                    runId, planId, AgentTeamRun.STATUS_FALLBACK, reason, true);
        }
        // 不调用 chat.close()：影子实例与主会话共享智能体，close/reset 会清掉主链路上下文。
    }

    private long recordFallback(
            long userId,
            long sessionId,
            long messageId,
            Long baselineRunId,
            String planId,
            String taskType,
            String templateId,
            String planJson,
            String catalogJson,
            String scoreJson,
            String reason,
            long started) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        String trimmed = truncate(reason, REASON_LIMIT);
        try {
            AgentTeamRun run =
                    new AgentTeamRun(
                            0L,
                            planId,
                            sessionId,
                            messageId,
                            userId,
                            AgentTeamRun.MODE_SHADOW,
                            taskType,
                            templateId,
                            planJson,
                            catalogJson,
                            scoreJson,
                            AgentTeamRun.STATUS_FALLBACK,
                            trimmed,
                            baselineRunId,
                            null,
                            null,
                            false,
                            elapsed,
                            LocalDateTime.now(),
                            null,
                            null,
                            null);
            long runId = teamStore.createRun(run);
            teamStore.finishRun(runId, AgentTeamRun.STATUS_FALLBACK, trimmed, elapsed);
            log.info(
                    "[动态团队] 影子回退记录：runId={}，message={}，reason={}",
                    runId,
                    messageId,
                    trimmed);
            return runId;
        } catch (RuntimeException e) {
            log.warn("[动态团队] 保存影子回退记录失败：{}", rootMessage(e));
            return 0L;
        }
    }

    private void finishFallback(long runId, String reason, long execStarted) {
        try {
            teamStore.finishRun(
                    runId,
                    AgentTeamRun.STATUS_FALLBACK,
                    truncate(reason, REASON_LIMIT),
                    Math.max(0L, System.currentTimeMillis() - execStarted));
        } catch (RuntimeException e) {
            log.warn("[动态团队] 更新影子回退状态失败：runId={}，{}", runId, rootMessage(e));
        }
    }

    private List<ChatToolTrace> persistTraces(
            long sessionId, long runId, List<ChatToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        try {
            return chatStore.appendTraces(sessionId, 0L, traces);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 保存影子追溯失败：runId={}，{}", runId, rootMessage(e));
            return List.of();
        }
    }

    private void persistStages(
            long runId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        if (stages == null) {
            return;
        }
        for (PayrollChat.StageRun stage : stages) {
            List<Long> traceIds =
                    traces.stream()
                            .filter(
                                    trace ->
                                            stage.nodeId().equals(trace.nodeId())
                                                    || stage.stageKey().equals(trace.agent()))
                            .map(ChatToolTrace::id)
                            .toList();
            try {
                teamStore.appendRecord(
                        new AgentRunRecord(
                                0L,
                                runId,
                                stage.nodeId(),
                                stage.stageKey(),
                                stage.agentId(),
                                stage.role(),
                                stage.status(),
                                stage.toolCalls(),
                                stage.toolErrors(),
                                stage.validationPassed(),
                                stage.outputChars(),
                                stage.durationMs(),
                                traceIds,
                                null,
                                null,
                                truncate(stage.errorMessage(), 512),
                                LocalDateTime.now()));
            } catch (RuntimeException e) {
                log.warn(
                        "[动态团队] 保存影子节点失败：runId={}，node={}，{}",
                        runId,
                        stage.nodeId(),
                        rootMessage(e));
            }
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        if (planner != null) {
            planner.shutdownNow();
        }
        if (flowExecutor != null) {
            flowExecutor.shutdownNow();
        }
    }

    /** 一次影子编排的结论；runId=0 表示未落库。 */
    public record ShadowOutcome(
            long runId, String planId, String status, String fallbackReason, boolean planned) {

        public static ShadowOutcome skipped(String reason) {
            return new ShadowOutcome(0L, "", "SKIPPED", reason, false);
        }

        public boolean recorded() {
            return runId > 0;
        }
    }
}
