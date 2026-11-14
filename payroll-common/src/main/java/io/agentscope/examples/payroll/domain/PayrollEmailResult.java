package io.agentscope.examples.payroll.domain;

/** 单个收件人的发送结果。 */
public record PayrollEmailResult(
        String employeeId, String employeeName, String email, String status, String reason) {
}
