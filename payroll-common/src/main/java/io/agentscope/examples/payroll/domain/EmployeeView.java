package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 管理台员工视图：在员工档案基础上附带启用状态与更新时间。 */
public record EmployeeView(
        @JsonProperty("employee_id") String employeeId,
        String name,
        String department,
        @JsonProperty("position") String position,
        @JsonProperty("base_salary") BigDecimal baseSalary,
        @JsonProperty("position_allowance") BigDecimal positionAllowance,
        @JsonProperty("housing_fund_rate") BigDecimal housingFundRate,
        @JsonProperty("social_insurance_base") BigDecimal socialInsuranceBase,
        @JsonProperty("special_additional_deduction") BigDecimal specialAdditionalDeduction,
        @JsonProperty("hire_date") LocalDate hireDate,
        @JsonProperty("bank_account_masked") String bankAccountMasked,
        String email,
        String city,
        boolean active,
        @JsonProperty("updated_at") String updatedAt) {

    public Employee toEmployee() {
        return new Employee(
                employeeId,
                name,
                department,
                position,
                baseSalary,
                positionAllowance,
                housingFundRate,
                socialInsuranceBase,
                specialAdditionalDeduction,
                hireDate,
                bankAccountMasked,
                email,
                city);
    }
}
