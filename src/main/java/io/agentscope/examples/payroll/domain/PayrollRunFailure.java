package io.agentscope.examples.payroll.domain;

/** 批次内核算失败的员工，失败原因必须原样展示给 HR。 */
public record PayrollRunFailure(String employeeId, String message) {
}
