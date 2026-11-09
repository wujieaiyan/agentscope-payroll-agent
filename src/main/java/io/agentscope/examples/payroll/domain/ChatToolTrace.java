package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次工具调用的可追溯快照：参数、结果、耗时与从结果中提取的金额/员工/期间。
 *
 * <p>id/messageId/sessionId 在落库前为 0，落库时由存储层补齐。runId/nodeId 指向
 * 本次团队运行与节点；旧数据或未启用团队运行时为 0/null。
 */
public record ChatToolTrace(
        long id,
        long messageId,
        long sessionId,
        String toolCallId,
        String toolName,
        String agent,
        long runId,
        String nodeId,
        String argsJson,
        String resultJson,
        boolean truncated,
        String resultState,
        long durationMs,
        List<String> employees,
        String period,
        String department,
        Map<String, BigDecimal> amounts,
        LocalDateTime createdAt) {

    public ChatToolTrace {
        employees = employees == null ? List.of() : List.copyOf(employees);
        amounts = amounts == null ? Map.of() : Map.copyOf(amounts);
    }

    /** 兼容旧调用方：未提供运行坐标时按旧数据语义落 0/null。 */
    public ChatToolTrace(
            long id,
            long messageId,
            long sessionId,
            String toolCallId,
            String toolName,
            String agent,
            String argsJson,
            String resultJson,
            boolean truncated,
            String resultState,
            long durationMs,
            List<String> employees,
            String period,
            String department,
            Map<String, BigDecimal> amounts,
            LocalDateTime createdAt) {
        this(
                id,
                messageId,
                sessionId,
                toolCallId,
                toolName,
                agent,
                0L,
                null,
                argsJson,
                resultJson,
                truncated,
                resultState,
                durationMs,
                employees,
                period,
                department,
                amounts,
                createdAt);
    }

    public static ChatToolTrace draft(
            String toolCallId,
            String toolName,
            String agent,
            String argsJson,
            String resultJson,
            boolean truncated,
            String resultState,
            long durationMs,
            List<String> employees,
            String period,
            String department,
            Map<String, BigDecimal> amounts) {
        return new ChatToolTrace(
                0L,
                0L,
                0L,
                toolCallId,
                toolName,
                agent,
                0L,
                null,
                argsJson,
                resultJson,
                truncated,
                resultState,
                durationMs,
                employees,
                period,
                department,
                amounts,
                LocalDateTime.now());
    }

    public ChatToolTrace withContext(long sessionId, long messageId) {
        return new ChatToolTrace(
                id,
                messageId,
                sessionId,
                toolCallId,
                toolName,
                agent,
                runId,
                nodeId,
                argsJson,
                resultJson,
                truncated,
                resultState,
                durationMs,
                employees,
                period,
                department,
                amounts,
                createdAt);
    }

    public ChatToolTrace withScope(TraceScope scope) {
        if (scope == null) {
            return this;
        }
        return new ChatToolTrace(
                id,
                messageId,
                sessionId,
                toolCallId,
                toolName,
                agent,
                scope.runId(),
                scope.nodeId(),
                argsJson,
                resultJson,
                truncated,
                resultState,
                durationMs,
                employees,
                period,
                department,
                amounts,
                createdAt);
    }
}
