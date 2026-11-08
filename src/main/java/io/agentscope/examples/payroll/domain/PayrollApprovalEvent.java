package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 批次审批轨迹：提交、逐节点通过、最终通过、驳回，作为审批工作流的执行证据。 */
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
        Integer stepOrder,
        String stepName,
        LocalDateTime createdAt) {

    public static final String SUBMIT = "SUBMIT";
    public static final String STEP_APPROVE = "STEP_APPROVE";
    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
}
