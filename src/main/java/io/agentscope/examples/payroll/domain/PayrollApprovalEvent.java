package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 批次审批轨迹：提交、通过、驳回各一条，作为审批链的执行证据。 */
public record PayrollApprovalEvent(
        long eventId,
        long runId,
        String action,
        Long actorId,
        String actorName,
        Long flowId,
        String flowName,
        String comment,
        String snapshotHash,
        LocalDateTime createdAt) {

    public static final String SUBMIT = "SUBMIT";
    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
}
