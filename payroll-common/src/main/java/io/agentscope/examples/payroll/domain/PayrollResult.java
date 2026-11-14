package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** 单人月度核算结果。所有金额均为系统计算结果，是智能体对外回答的唯一事实来源。 */
public record PayrollResult(
        @JsonProperty("employee_id") String employeeId,
        @JsonProperty("employee_name") String employeeName,
        String department,
        String period,
        @JsonProperty("base_salary") BigDecimal baseSalary,
        @JsonProperty("position_allowance") BigDecimal positionAllowance,
        @JsonProperty("overtime_pay") BigDecimal overtimePay,
        @JsonProperty("performance_bonus") BigDecimal performanceBonus,
        @JsonProperty("other_allowance") BigDecimal otherAllowance,
        @JsonProperty("gross_pay") BigDecimal grossPay,
        @JsonProperty("attendance_deduction") BigDecimal attendanceDeduction,
        @JsonProperty("taxable_gross") BigDecimal taxableGross,
        @JsonProperty("social_insurance_base") BigDecimal socialInsuranceBase,
        @JsonProperty("social_insurance_employee") BigDecimal socialInsuranceEmployee,
        @JsonProperty("housing_fund_employee") BigDecimal housingFundEmployee,
        @JsonProperty("social_insurance_employer") BigDecimal socialInsuranceEmployer,
        @JsonProperty("housing_fund_employer") BigDecimal housingFundEmployer,
        @JsonProperty("special_additional_deduction") BigDecimal specialAdditionalDeduction,
        @JsonProperty("total_deductions") BigDecimal totalDeductions,
        @JsonProperty("net_pay") BigDecimal netPay,
        @JsonProperty("employer_cost") BigDecimal employerCost,
        TaxDetail tax,
        List<String> warnings) {

    public PayrollResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
