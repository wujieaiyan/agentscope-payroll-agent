package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.team.TeamSettings;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态链路的团队运行记录：一轮对话一条 run，计算/验证/核算各一条节点记录。
 *
 * <p>只记录旁路数据，任何写入失败都不影响对话主链路；共享智能体不会被关闭。
 */
public final class StaticRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(StaticRunRecorder.class);

    public record ActiveRun(long runId, String planId, long startedAt) {
    }

    private static final String TEMPLATE_ID = "static-payroll-pipeline";
    private static final int FALLBACK_REASON_LIMIT = 256;
    private static final int ERROR_MESSAGE_LIMIT = 512;

    private final H2AgentTeamStore store;
    private final TeamSettings settings;

    public StaticRunRecorder(H2AgentTeamStore store, TeamSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    public static StaticRunRecorder disabled() {
        return new StaticRunRecorder(null, null);
    }

    public boolean enabled() {
        return store != null && settings != null && settings.enabled();
    }

    public ActiveRun start(long sessionId, long userId) {
        String planId = "static-" + sessionId + "-" + System.nanoTime();
        AgentTeamRun run =
                new AgentTeamRun(
                        0L,
                        planId,
                        sessionId,
                        0L,
                        userId,
                        AgentTeamRun.MODE_STATIC,
                        "PAYROLL",
                        TEMPLATE_ID,
                        planJson(planId),
                        null,
                        null,
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
                        null);
        return new ActiveRun(store.createRun(run), planId, System.currentTimeMillis());
    }

    public TraceScope scope(ActiveRun run) {
        return new TraceScope(
                run.runId(),
                run.planId(),
                null,
                null,
                null,
                AgentTeamRun.MODE_STATIC,
                null);
    }

    public void succeed(
            ActiveRun run,
            long messageId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        finish(run, messageId, AgentTeamRun.STATUS_SUCCESS, null, stages, traces);
    }

    public void fail(
            ActiveRun run,
            long messageId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces,
            String reason) {
        finish(run, messageId, AgentTeamRun.STATUS_FAILED, reason, stages, traces);
    }

    public void interrupted(
            ActiveRun run,
            long messageId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        finish(
                run,
                messageId,
                AgentTeamRun.STATUS_INTERRUPTED,
                "会话在回答完成前被释放",
                stages,
                traces);
    }

    private void finish(
            ActiveRun run,
            long messageId,
            String status,
            String reason,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        long totalMs = Math.max(0L, System.currentTimeMillis() - run.startedAt());
        if (messageId > 0) {
            store.bindMessage(run.runId(), messageId);
        }
        store.finishRun(
                run.runId(), status, truncate(reason, FALLBACK_REASON_LIMIT), totalMs);
        store.markPrimary(run.runId());
        persistStages(run.runId(), stages, traces);
        log.info(
                "[动态团队] 静态运行已保存：runId={}，messageId={}，status={}，节点={}，trace={}，耗时={}ms",
                run.runId(),
                messageId,
                status,
                stages == null ? 0 : stages.size(),
                traces == null ? 0 : traces.size(),
                totalMs);
    }

    private void persistStages(
            long runId, List<PayrollChat.StageRun> stages, List<ChatToolTrace> traces) {
        List<ChatToolTrace> persisted = traces == null ? List.of() : traces;
        if (stages == null || stages.isEmpty()) {
            store.appendRecord(
                    new AgentRunRecord(
                            0L,
                            runId,
                            "pipeline",
                            "pipeline",
                            null,
                            null,
                            AgentRunRecord.STATUS_FAILED,
                            0,
                            0,
                            null,
                            0,
                            0,
                            List.of(),
                            null,
                            null,
                            "未采集到阶段摘要",
                            LocalDateTime.now()));
            return;
        }
        for (PayrollChat.StageRun stage : stages) {
            List<Long> traceIds =
                    persisted.stream()
                            .filter(trace -> matches(trace, stage))
                            .map(ChatToolTrace::id)
                            .toList();
            store.appendRecord(
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
                            truncate(stage.errorMessage(), ERROR_MESSAGE_LIMIT),
                            LocalDateTime.now()));
        }
    }

    private static boolean matches(ChatToolTrace trace, PayrollChat.StageRun stage) {
        if (trace.nodeId() != null && !trace.nodeId().isBlank()) {
            return trace.nodeId().equals(stage.nodeId());
        }
        return stage.stageKey().equals(trace.agent());
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

    private static String planJson(String planId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("plan_id", planId);
        plan.put("template_id", TEMPLATE_ID);
        plan.put("kind", "STATIC_PIPELINE");
        plan.put(
                "nodes",
                List.of(
                        node("calculator", "CALCULATOR", "PayrollCalculatorAgent"),
                        node("validator", "VALIDATOR", "PayrollValidatorAgent"),
                        node("accountant", "ACCOUNTANT", "PayrollAccountantAgent")));
        return JsonSupport.toJson(plan);
    }

    private static Map<String, Object> node(String id, String role, String agentId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "AGENT");
        node.put("role", role);
        node.put("agent_id", agentId);
        return node;
    }
}
