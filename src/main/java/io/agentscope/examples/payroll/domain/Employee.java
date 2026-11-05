package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 员工薪酬档案。
 *
 * @param socialInsuranceBase 社保缴费基数；为空时按“基本工资 + 岗位津贴”并受政策上下限约束
 * @param specialAdditionalDeduction 个税专项附加扣除合计（月度，已由员工申报确认）
 */
public record Employee(
        @JsonProperty("employee_id") String employeeId,
        String name,
        String department,
        String position,
        @JsonProperty("base_salary") BigDecimal baseSalary,
        @JsonProperty("position_allowance") BigDecimal positionAllowance,
        @JsonProperty("housing_fund_rate") BigDecimal housingFundRate,
        @JsonProperty("social_insurance_base") BigDecimal socialInsuranceBase,
        @JsonProperty("special_additional_deduction") BigDecimal specialAdditionalDeduction,
        @JsonProperty("hire_date") LocalDate hireDate,
        @JsonProperty("bank_account_masked") String bankAccountMasked) {

    public Employee {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(department, "department");
        Objects.requireNonNull(hireDate, "hireDate");
        if (employeeId.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("employeeId/name must not be blank");
        }
        baseSalary = Money.round(baseSalary);
        positionAllowance = Money.round(positionAllowance);
        specialAdditionalDeduction = Money.round(specialAdditionalDeduction);
        housingFundRate = Money.nz(housingFundRate);
        socialInsuranceBase =
                socialInsuranceBase == null ? null : Money.round(socialInsuranceBase);
        if (baseSalary.signum() < 0 || positionAllowance.signum() < 0) {
            throw new IllegalArgumentException("salary components must be non-negative");
        }
        if (housingFundRate.signum() < 0 || housingFundRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("housingFundRate must be within [0, 1]");
        }
    }
}
