package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.service.PayrollChat;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把一轮对话的阶段摘要落成节点记录，并关联本 run 的 trace id。 */
final class TeamRunRecords {

    private static final Logger log = LoggerFactory.getLogger(TeamRunRecords.class);
    private static final int ERROR_MESSAGE_LIMIT = 512;

    private TeamRunRecords() {
    }

    static void persistStages(
            H2AgentTeamStore store,
            long runId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        List<ChatToolTrace> persisted = traces == null ? List.of() : traces;
        for (PayrollChat.StageRun stage : stages) {
            List<Long> traceIds =
                    persisted.stream()
                            .filter(trace -> matches(trace, stage))
                            .map(ChatToolTrace::id)
                            .toList();
            try {
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
                                truncate(stage.errorMessage()),
                                LocalDateTime.now()));
            } catch (RuntimeException e) {
                log.warn(
                        "[动态团队] 保存节点失败：runId={}，node={}，{}",
                        runId,
                        stage.nodeId(),
                        e.getMessage());
            }
        }
    }

    private static boolean matches(ChatToolTrace trace, PayrollChat.StageRun stage) {
        if (trace.nodeId() != null && !trace.nodeId().isBlank()) {
            return trace.nodeId().equals(stage.nodeId());
        }
        return stage.stageKey().equals(trace.agent());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= ERROR_MESSAGE_LIMIT
                ? trimmed
                : trimmed.substring(0, ERROR_MESSAGE_LIMIT);
    }
}
