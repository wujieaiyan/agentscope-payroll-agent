package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 批次的审批快照：提交人、审批人、驳回原因与提交时的金额指纹。 */
public record PayrollApproval(
        Long flowId,
        String flowName,
        Long submittedById,
        String submittedByName,
        LocalDateTime submittedAt,
        String submitComment,
        Long approvedById,
        String approvedByName,
        LocalDateTime approvedAt,
        String approvalComment,
        Long rejectedById,
        String rejectedByName,
        LocalDateTime rejectedAt,
        String rejectionReason,
        String snapshotHash) {

    public static PayrollApproval empty() {
        return new PayrollApproval(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }
}
