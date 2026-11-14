package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACTIVE 模式同步编排：在白名单内先尝试动态组队，成功返回动态结果，任何失败都回退静态链。
 *
 * <p>与影子模式不同，这里占用用户请求线程；组队耗时包含意图与计划两次轻量模型调用，
 * 执行仍复用 DAG 引擎的单节点超时与追溯。运行落库为 {@code mode=ACTIVE}，
 * 成功时由会话层标记为消息主运行，回退时通过 {@code fallback_of_run_id} 指向静态运行。
 */
public final class ActiveTeamService implements ActiveTeamCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ActiveTeamService.class);
    private static final int REASON_LIMIT = 256;

    private final TeamSettings settings;
    private final H2AgentTeamStore teamStore;
    private final TeamPlanPipeline pipeline;
    private final AuditService auditService;
    private final boolean enabled;

    public ActiveTeamService(
            TeamSettings settings,
            H2AgentTeamStore teamStore,
            AgentStatsService statsService,
            Supplier<List<AgentDefinition>> catalogSupplier,
            TeamLlmClient llm,
            AuditService auditService) {
        this.settings =
                settings == null ? new TeamSettings(TeamMode.OFF, 0, 0, 0D) : settings;
        this.teamStore = teamStore;
        this.auditService = auditService;
        this.pipeline =
                new TeamPlanPipeline(this.settings, catalogSupplier, statsService, llm);
        this.enabled = this.settings.mode() == TeamMode.ACTIVE && teamStore != null;
        if (enabled) {
            log.info("[动态团队] ACTIVE 编排已启用");
        }
    }

    public static ActiveTeamService disabled() {
        return new ActiveTeamService(
                new TeamSettings(TeamMode.OFF, 0, 0, 0D),
                null,
                null,
                List::of,
                TeamLlmClient.disabled(),
                null);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allowed(long userId, long sessionId) {
        return enabled && settings.activeAllowed(userId, sessionId);
    }

    /**
     * 同步尝试一次动态组队并执行。调用方必须持有对话实例的会话锁；
     * 返回成功时答复已生成，返回回退时调用方继续走静态链路。
     */
    public Attempt attempt(
            PayrollChatService chat,
            long userId,
            long sessionId,
            String question,
            String executionInput,
            ChatStreamListener listener) {
        return attempt(chat, userId, sessionId, question, executionInput, listener, false);
    }

    /** {@code kbBound=true} 时，制度/条款类问题按 KB_CONFIG 模板组队。 */
    public Attempt attempt(
            PayrollChatService chat,
            long userId,
            long sessionId,
            String question,
            String executionInput,
            ChatStreamListener listener,
            boolean kbBound) {
        if (!allowed(userId, sessionId)) {
            return Attempt.skipped();
        }
        long started = System.currentTimeMillis();
        String planId = "active-" + sessionId + "-" + System.nanoTime();
        TeamPlanPipeline.Attempt prepared;
        try {
            prepared = pipeline.prepare(question, planId, kbBound);
        } catch (RuntimeException e) {
            log.warn("[动态团队] ACTIVE 组队准备失败，回退静态：{}", rootMessage(e));
            return recordFallback(
                    userId,
                    sessionId,
                    planId,
                    null,
                    null,
                    "L2: 组队准备失败：" + rootMessage(e),
                    started);
        }
        if (prepared instanceof TeamPlanPipeline.Fallback fallback) {
            log.info(
                    "[动态团队] ACTIVE 组队回退：session={}，原因={}",
                    sessionId,
                    fallback.reason());
            return recordFallback(
                    userId,
                    sessionId,
                    planId,
                    fallback.intent() == null ? null : fallback.intent().taskType(),
                    fallback.templateId(),
                    fallback.reason(),
                    started);
        }

        TeamPlanPipeline.Prepared plan = (TeamPlanPipeline.Prepared) prepared;
        long runId;
        try {
            runId =
                    teamStore.createRun(
                            new AgentTeamRun(
                                    0L,
                                    planId,
                                    sessionId,
                                    0L,
                                    userId,
                                    AgentTeamRun.MODE_ACTIVE,
                                    plan.intent().taskType(),
                                    plan.template().id(),
                                    JsonSupport.toJson(plan.plan()),
                                    plan.catalogJson(),
                                    plan.scoreJson(),
                                    AgentTeamRun.STATUS_RUNNING,
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    0L,
                                    LocalDateTime.now(),
                                    null,
                                    null,
                                    null));
        } catch (RuntimeException e) {
            log.warn("[动态团队] 创建 ACTIVE 运行失败，回退静态：{}", rootMessage(e));
            return Attempt.skipped();
        }

        TraceScope scope =
                new TraceScope(
                        runId, planId, null, null, null, AgentTeamRun.MODE_ACTIVE, null);
        long execStarted = System.currentTimeMillis();
        try {
            listener.onPhase("动态团队：" + plan.flow().name());
            PayrollChatService.DynamicFlowResult result =
                    chat.executeDynamicFlow(
                            plan.plan(),
                            plan.flow(),
                            plan.agents(),
                            executionInput,
                            scope,
                            listener);
            String reply = result.reply() == null ? "" : result.reply().trim();
            if (reply.isEmpty()) {
                return finishFallback(
                        runId, planId, plan.template().id(), "L3: 动态流程未产生答复", execStarted);
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - execStarted);
            finishRun(runId, AgentTeamRun.STATUS_SUCCESS, null, elapsed);
            audit(
                    "team.active.executed",
                    runId,
                    plan,
                    planId,
                    null);
            log.info(
                    "[动态团队] ACTIVE 执行完成：runId={}，plan={}，template={}，节点={}，trace={}，耗时={}ms",
                    runId,
                    planId,
                    plan.template().id(),
                    result.stages().size(),
                    result.traces().size(),
                    System.currentTimeMillis() - started);
            return new Attempt(
                    runId,
                    planId,
                    AgentTeamRun.STATUS_SUCCESS,
                    null,
                    plan.template().id(),
                    reply,
                    result.tools(),
                    result.traces(),
                    List.copyOf(plan.agents().keySet()),
                    result.streamedText(),
                    result.citations());
        } catch (RuntimeException e) {
            log.warn(
                    "[动态团队] ACTIVE 执行失败，回退静态：runId={}，plan={}，原因={}",
                    runId,
                    planId,
                    rootMessage(e));
            return finishFallback(
                    runId,
                    planId,
                    plan.template().id(),
                    "L3: 动态流程执行失败：" + rootMessage(e),
                    execStarted);
        }
    }

    /** 成功运行落主运行：绑定消息、标记 primary，并写入节点记录。 */
    public void completeSuccess(
            PayrollChat.TeamReply team,
            long messageId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        if (team == null || team.runId() <= 0 || teamStore == null) {
            return;
        }
        try {
            teamStore.bindMessage(team.runId(), messageId);
            teamStore.markPrimary(team.runId());
            TeamRunRecords.persistStages(teamStore, team.runId(), stages, traces);
            log.info(
                    "[动态团队] ACTIVE 主运行已绑定：runId={}，message={}，节点={}，trace={}",
                    team.runId(),
                    messageId,
                    stages == null ? 0 : stages.size(),
                    traces == null ? 0 : traces.size());
        } catch (RuntimeException e) {
            log.warn(
                    "[动态团队] 保存 ACTIVE 主运行失败：runId={}，{}",
                    team.runId(),
                    rootMessage(e));
        }
    }

    /** 回退运行补绑消息，并记录它回退到哪条静态运行。 */
    public void completeFallback(PayrollChat.TeamReply team, long messageId, long staticRunId) {
        if (team == null || team.runId() <= 0 || teamStore == null) {
            return;
        }
        try {
            teamStore.bindMessage(team.runId(), messageId);
            if (staticRunId > 0) {
                teamStore.linkFallback(team.runId(), staticRunId);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "[动态团队] 记录 ACTIVE 回退失败：runId={}，{}",
                    team.runId(),
                    rootMessage(e));
        }
    }

    private Attempt recordFallback(
            long userId,
            long sessionId,
            String planId,
            String taskType,
            String templateId,
            String reason,
            long started) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        String trimmed = truncate(reason);
        try {
            long runId =
                    teamStore.createRun(
                            new AgentTeamRun(
                                    0L,
                                    planId,
                                    sessionId,
                                    0L,
                                    userId,
                                    AgentTeamRun.MODE_ACTIVE,
                                    taskType,
                                    templateId,
                                    "{}",
                                    null,
                                    null,
                                    AgentTeamRun.STATUS_FALLBACK,
                                    trimmed,
                                    null,
                                    null,
                                    null,
                                    false,
                                    elapsed,
                                    LocalDateTime.now(),
                                    null,
                                    null,
                                    null));
            finishRun(runId, AgentTeamRun.STATUS_FALLBACK, trimmed, elapsed);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("plan_id", planId);
            snapshot.put("template_id", templateId);
            snapshot.put("fallback_reason", trimmed);
            audit("team.active.fallback", runId, null, planId, snapshot);
            log.info(
                    "[动态团队] ACTIVE 回退已记录：runId={}，reason={}",
                    runId,
                    trimmed);
            return new Attempt(
                    runId,
                    planId,
                    AgentTeamRun.STATUS_FALLBACK,
                    trimmed,
                    templateId,
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    List.of());
        } catch (RuntimeException e) {
            log.warn("[动态团队] 保存 ACTIVE 回退失败：{}", rootMessage(e));
            return Attempt.skipped();
        }
    }

    private Attempt finishFallback(
            long runId, String planId, String templateId, String reason, long execStarted) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - execStarted);
        String trimmed = truncate(reason);
        finishRun(runId, AgentTeamRun.STATUS_FALLBACK, trimmed, elapsed);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("plan_id", planId);
        snapshot.put("template_id", templateId);
        snapshot.put("fallback_reason", trimmed);
        audit("team.active.fallback", runId, null, planId, snapshot);
        log.info(
                "[动态团队] ACTIVE 回退已记录：runId={}，reason={}",
                runId,
                trimmed);
        return new Attempt(
                runId,
                planId,
                AgentTeamRun.STATUS_FALLBACK,
                trimmed,
                templateId,
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of());
    }

    private void finishRun(long runId, String status, String reason, long elapsed) {
        try {
            teamStore.finishRun(runId, status, reason, elapsed);
        } catch (RuntimeException e) {
            log.warn(
                    "[动态团队] 更新 ACTIVE 运行状态失败：runId={}，{}",
                    runId,
                    rootMessage(e));
        }
    }

    private void audit(
            String action,
            long runId,
            TeamPlanPipeline.Prepared plan,
            String planId,
            Map<String, Object> extra) {
        if (auditService == null) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("plan_id", planId);
        snapshot.put("mode", AgentTeamRun.MODE_ACTIVE);
        if (plan != null) {
            snapshot.put("template_id", plan.template().id());
            snapshot.put("agent_ids", List.copyOf(plan.agents().keySet()));
        }
        if (extra != null) {
            snapshot.putAll(extra);
        }
        auditService.record(action, "agent_team_run", Long.toString(runId), null, snapshot);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= REASON_LIMIT ? trimmed : trimmed.substring(0, REASON_LIMIT);
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

    /** 一次 ACTIVE 尝试；runId=0 表示未进入动态链路（静态照常执行）。 */
    public record Attempt(
            long runId,
            String planId,
            String status,
            String fallbackReason,
            String templateId,
            String reply,
            List<String> tools,
            List<ChatToolTrace> traces,
            List<String> agentIds,
            String streamedText,
            List<Citation> citations) {

        public Attempt {
            planId = planId == null ? "" : planId;
            status = status == null ? "" : status;
            templateId = templateId == null ? "" : templateId;
            reply = reply == null ? "" : reply;
            tools = tools == null ? List.of() : List.copyOf(tools);
            traces = traces == null ? List.of() : List.copyOf(traces);
            agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
            streamedText = streamedText == null ? "" : streamedText;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public static Attempt skipped() {
            return new Attempt(
                    0L,
                    "",
                    "SKIPPED",
                    null,
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    List.of());
        }

        public boolean recorded() {
            return runId > 0;
        }

        public boolean succeeded() {
            return AgentTeamRun.STATUS_SUCCESS.equals(status);
        }

        public PayrollChat.TeamReply teamReply() {
            return recorded()
                    ? new PayrollChat.TeamReply(
                            runId,
                            planId,
                            AgentTeamRun.MODE_ACTIVE,
                            status,
                            templateId,
                            fallbackReason,
                            agentIds)
                    : null;
        }
    }
}
