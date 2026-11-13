package io.agentscope.examples.payroll.domain;

import java.util.List;

/** 审计日志分页结果。 */
public record AuditPage(List<AuditEntry> items, long total, int page, int size) {
}
