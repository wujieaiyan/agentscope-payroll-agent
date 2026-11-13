package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 团队运行中的单个节点记录，traceIds 指向本次节点产生的工具追溯。 */
public record AgentRunRecord(
        long id,
        long runId,
        String nodeId,
        String stageKey,
        String agentId,
        String role,
        String status,
        int toolCalls,
        int toolErrors,
        Boolean validationPassed,
        int outputChars,
        long durationMs,
        List<Long> traceIds,
        String inputDigest,
        String messageJson,
        String errorMessage,
        LocalDateTime createdAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_EMPTY = "EMPTY";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public AgentRunRecord {
        nodeId = nodeId == null ? "" : nodeId;
        status = status == null ? STATUS_SUCCESS : status;
        traceIds = traceIds == null ? List.of() : List.copyOf(traceIds);
    }

    public AgentRunRecord withTraceIds(List<Long> ids) {
        return new AgentRunRecord(
                id,
                runId,
                nodeId,
                stageKey,
                agentId,
                role,
                status,
                toolCalls,
                toolErrors,
                validationPassed,
                outputChars,
                durationMs,
                ids,
                inputDigest,
                messageJson,
                errorMessage,
                createdAt);
    }
}
