package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 一条审计日志：谁在什么时间、从哪里、对什么资源做了什么，以及变更前后快照。 */
public record AuditEntry(
        long id,
        LocalDateTime occurredAt,
        Long actorId,
        String actorName,
        String action,
        String resourceType,
        String resourceId,
        String beforeJson,
        String afterJson,
        String payloadJson,
        String ip,
        String userAgent,
        String result,
        Integer statusCode,
        String message,
        String traceId) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    /** 新建待落库的日志，id 由数据库生成。 */
    public static AuditEntry pending(
            Long actorId,
            String actorName,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson,
            String payloadJson,
            String ip,
            String userAgent,
            String result,
            Integer statusCode,
            String message,
            String traceId) {
        return new AuditEntry(
                0L,
                LocalDateTime.now(),
                actorId,
                actorName,
                action,
                resourceType,
                resourceId,
                beforeJson,
                afterJson,
                payloadJson,
                ip,
                userAgent,
                result,
                statusCode,
                message,
                traceId);
    }
}
