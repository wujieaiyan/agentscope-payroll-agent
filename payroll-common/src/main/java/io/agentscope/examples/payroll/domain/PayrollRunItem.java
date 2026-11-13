package io.agentscope.examples.payroll.domain;

/** 批次内单名员工的核算结果与邮件状态。 */
public record PayrollRunItem(
        String employeeId,
        String employeeName,
        String department,
        String city,
        String email,
        PayrollAdjustment adjustment,
        PayrollResult payroll,
        String emailStatus,
        String emailReason) {
}
