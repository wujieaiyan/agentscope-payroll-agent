package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 审计日志查询条件；actor 按用户名模糊匹配，keyword 覆盖动作/资源/消息。 */
public record AuditQuery(
        LocalDateTime from,
        LocalDateTime to,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        String result,
        String keyword,
        int page,
        int size) {

    public AuditQuery {
        page = Math.max(1, page);
        size = Math.max(1, size);
    }

    public static AuditQuery firstPage(int size) {
        return new AuditQuery(null, null, null, null, null, null, null, null, 1, size);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public AuditQuery withPage(int newPage, int newSize) {
        return new AuditQuery(
                from,
                to,
                actor,
                action,
                resourceType,
                resourceId,
                result,
                keyword,
                newPage,
                newSize);
    }
}
