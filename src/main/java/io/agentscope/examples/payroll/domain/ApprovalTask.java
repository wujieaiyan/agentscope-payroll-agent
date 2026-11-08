package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 当前用户待处理的审批任务：批次停在某个节点上等待处理。 */
public record ApprovalTask(
        long runId,
        String period,
        String scope,
        BigDecimal totalNet,
        String flowName,
        Long submittedById,
        String submittedByName,
        LocalDateTime submittedAt,
        int currentStep,
        int stepCount,
        ApprovalStep step) {
}
