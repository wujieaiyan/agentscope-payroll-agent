package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G2 任务级审批批次（P0-8）：一次确认覆盖一个计划的多类补丁，按任务逐项放行或打回。
 *
 * <p>批次是唯一人工门，替代开发域里 {@code ActionGuard} 的单轮单写提案；审批只改变
 * 批次状态，补丁应用发生在审批通过之后。
 */
public record DevApprovalBatch(
        String batchId,
        String planId,
        String summary,
        DevApprovalStatus status,
        List<DevApprovalItem> items,
        String decidedBy,
        String note,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {

    public DevApprovalBatch {
        batchId = batchId == null ? "" : batchId.trim();
        planId = planId == null ? "" : planId.trim();
        summary = summary == null ? "" : summary.trim();
        status = status == null ? DevApprovalStatus.PENDING : status;
        items = items == null ? List.of() : List.copyOf(items);
        decidedBy = decidedBy == null ? "" : decidedBy.trim();
        note = note == null ? "" : note.trim();
    }

    public boolean pending() {
        return status == DevApprovalStatus.PENDING;
    }

    public DevApprovalBatch withDecision(
            DevApprovalStatus next,
            List<DevApprovalItem> nextItems,
            String by,
            String reason,
            LocalDateTime at) {
        return new DevApprovalBatch(
                batchId, planId, summary, next, nextItems, by, reason, createdAt, at);
    }
}
