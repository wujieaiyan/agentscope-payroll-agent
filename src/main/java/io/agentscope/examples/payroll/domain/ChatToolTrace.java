package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次工具调用的可追溯快照：参数、结果、耗时与从结果中提取的金额/员工/期间。
 *
 * <p>id/messageId/sessionId 在落库前为 0，落库时由存储层补齐。
 */
public record ChatToolTrace(
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

    public ChatToolTrace {
        employees = employees == null ? List.of() : List.copyOf(employees);
        amounts = amounts == null ? Map.of() : Map.copyOf(amounts);
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
