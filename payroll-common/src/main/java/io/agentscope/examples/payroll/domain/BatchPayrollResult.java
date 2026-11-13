package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** 部门/全员批量核算结果。失败员工单独列出，不阻断其他员工出账。 */
public record BatchPayrollResult(
        String period,
        String department,
        @JsonProperty("employee_count") int employeeCount,
        @JsonProperty("total_gross") BigDecimal totalGross,
        @JsonProperty("total_deductions") BigDecimal totalDeductions,
        @JsonProperty("total_net") BigDecimal totalNet,
        @JsonProperty("total_employer_cost") BigDecimal totalEmployerCost,
        List<PayrollResult> records,
        List<BatchFailure> failures) {

    public record BatchFailure(
            @JsonProperty("employee_id") String employeeId, String message) {
    }
}
