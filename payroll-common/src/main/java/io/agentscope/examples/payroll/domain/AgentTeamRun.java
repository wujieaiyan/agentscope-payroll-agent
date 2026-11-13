package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/**
 * 一次团队运行（静态三阶段也按一次运行记录）。
 *
 * <p>团队是临时运行单元，智能体是共享资产；运行结束后只关闭本次运行，不关闭共享智能体。
 */
public record AgentTeamRun(
        long id,
        String planId,
        long sessionId,
        long messageId,
        long userId,
        String mode,
        String taskType,
        String templateId,
        String planJson,
        String catalogSnapshotJson,
        String scoreSnapshotJson,
        String status,
        String fallbackReason,
        Long baselineRunId,
        Long fallbackOfRunId,
        Long retryOfRunId,
        boolean primary,
        long totalMs,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        LocalDateTime archivedAt,
        LocalDateTime deletedAt) {

    public static final String MODE_OFF = TraceScope.MODE_OFF;
    public static final String MODE_STATIC = TraceScope.MODE_STATIC;
    public static final String MODE_SHADOW = TraceScope.MODE_SHADOW;
    public static final String MODE_ACTIVE = TraceScope.MODE_ACTIVE;

    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    public static final String STATUS_FALLBACK = "FALLBACK";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CLOSED = "CLOSED";

    public AgentTeamRun {
        planId = planId == null ? "" : planId;
        mode = mode == null ? MODE_OFF : mode;
        planJson = planJson == null ? "{}" : planJson;
        status = status == null ? STATUS_PLANNED : status;
    }

    /** 静态链路运行：消息落库后补建，runId 由存储层返回。 */
    public static AgentTeamRun plannedStatic(
            long sessionId, long messageId, long userId, String planId, String planJson) {
        return new AgentTeamRun(
                0L,
                planId,
                sessionId,
                messageId,
                userId,
                MODE_STATIC,
                null,
                null,
                planJson,
                null,
                null,
                STATUS_PLANNED,
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
    }
}
