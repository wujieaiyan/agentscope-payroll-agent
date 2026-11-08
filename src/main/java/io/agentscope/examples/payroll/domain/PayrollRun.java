package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次工资批次的表头与审批快照。 */
public record PayrollRun(
        long runId,
        String period,
        String scope,
        String status,
        int employeeCount,
        BigDecimal totalGross,
        BigDecimal totalDeductions,
        BigDecimal totalNet,
        BigDecimal totalEmployerCost,
        String excelPath,
        LocalDateTime createdAt,
        LocalDateTime finalizedAt,
        PayrollApproval approval) {
}
