package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 批次的审批快照：提交人、审批人、驳回原因、金额指纹与提交时的节点快照。 */
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
        String snapshotHash,
        List<ApprovalStep> workflowSteps,
        int currentStep) {

    public PayrollApproval {
        workflowSteps = workflowSteps == null ? List.of() : List.copyOf(workflowSteps);
    }

    public static PayrollApproval empty() {
        return new PayrollApproval(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(), 0);
    }
}
