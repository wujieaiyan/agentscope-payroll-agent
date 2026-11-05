package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * 薪酬政策参数。
 *
 * <p>税率表与社保比例是“某一城市、某一社保年度”的口径，生产环境应从配置中心或政策库加载，
 * 本类中的默认值仅用于演示，不代表任何真实地区的现行政策。
 */
public record PayrollPolicy(
        @JsonProperty("monthly_threshold") BigDecimal monthlyThreshold,
        @JsonProperty("standard_work_days") BigDecimal standardWorkDays,
        @JsonProperty("standard_daily_hours") BigDecimal standardDailyHours,
        @JsonProperty("sick_leave_pay_rate") BigDecimal sickLeavePayRate,
        @JsonProperty("social_base_lower") BigDecimal socialBaseLower,
        @JsonProperty("social_base_upper") BigDecimal socialBaseUpper,
        @JsonProperty("pension_employee_rate") BigDecimal pensionEmployeeRate,
        @JsonProperty("medical_employee_rate") BigDecimal medicalEmployeeRate,
        @JsonProperty("medical_fixed_amount") BigDecimal medicalFixedAmount,
        @JsonProperty("unemployment_employee_rate") BigDecimal unemploymentEmployeeRate,
        @JsonProperty("pension_employer_rate") BigDecimal pensionEmployerRate,
        @JsonProperty("medical_employer_rate") BigDecimal medicalEmployerRate,
        @JsonProperty("unemployment_employer_rate") BigDecimal unemploymentEmployerRate,
        @JsonProperty("injury_employer_rate") BigDecimal injuryEmployerRate,
        @JsonProperty("maternity_employer_rate") BigDecimal maternityEmployerRate,
        @JsonProperty("tax_brackets") List<TaxBracket> taxBrackets) {

    public PayrollPolicy {
        taxBrackets = List.copyOf(taxBrackets);
        if (taxBrackets.isEmpty()) {
            throw new IllegalArgumentException("taxBrackets must not be empty");
        }
        TaxBracket last = taxBrackets.get(taxBrackets.size() - 1);
        if (last.upperLimit() != null) {
            throw new IllegalArgumentException("last tax bracket must be open-ended");
        }
        if (standardWorkDays.signum() <= 0 || standardDailyHours.signum() <= 0) {
            throw new IllegalArgumentException("standard work days/hours must be positive");
        }
    }

    /** 2026 演示口径，生产环境请替换为目标城市社保年度参数。 */
    public static PayrollPolicy demoPolicy() {
        return new PayrollPolicy(
                new BigDecimal("5000.00"),
                new BigDecimal("21.75"),
                new BigDecimal("8"),
                new BigDecimal("0.60"),
                new BigDecimal("7500.00"),
                new BigDecimal("37500.00"),
                new BigDecimal("0.08"),
                new BigDecimal("0.02"),
                new BigDecimal("3.00"),
                new BigDecimal("0.005"),
                new BigDecimal("0.16"),
                new BigDecimal("0.098"),
                new BigDecimal("0.005"),
                new BigDecimal("0.002"),
                new BigDecimal("0.008"),
                List.of(
                        new TaxBracket(new BigDecimal("36000.00"), new BigDecimal("0.03"), BigDecimal.ZERO),
                        new TaxBracket(new BigDecimal("144000.00"), new BigDecimal("0.10"), new BigDecimal("2520.00")),
                        new TaxBracket(new BigDecimal("300000.00"), new BigDecimal("0.20"), new BigDecimal("16920.00")),
                        new TaxBracket(new BigDecimal("420000.00"), new BigDecimal("0.25"), new BigDecimal("31920.00")),
                        new TaxBracket(new BigDecimal("660000.00"), new BigDecimal("0.30"), new BigDecimal("52920.00")),
                        new TaxBracket(new BigDecimal("960000.00"), new BigDecimal("0.35"), new BigDecimal("85920.00")),
                        new TaxBracket(null, new BigDecimal("0.45"), new BigDecimal("181920.00"))));
    }

    /** 按累计预扣法计算累计应纳税额，结果保留两位小数。 */
    public TaxComputation taxFor(BigDecimal cumulativeTaxableIncome) {
        BigDecimal taxable = Money.maxZero(cumulativeTaxableIncome);
        for (TaxBracket bracket : taxBrackets) {
            if (bracket.covers(taxable)) {
                BigDecimal tax = taxable.multiply(bracket.rate()).subtract(bracket.quickDeduction());
                return new TaxComputation(
                        Money.round(Money.maxZero(tax)), bracket.rate(), bracket.quickDeduction());
            }
        }
        throw new IllegalStateException("tax brackets do not cover taxable income: " + taxable);
    }

    public BigDecimal clampSocialBase(BigDecimal candidate) {
        BigDecimal base = Money.nz(candidate);
        if (base.signum() <= 0) {
            throw new IllegalArgumentException("social insurance base must be positive");
        }
        if (base.compareTo(socialBaseLower) < 0) {
            return socialBaseLower;
        }
        if (base.compareTo(socialBaseUpper) > 0) {
            return socialBaseUpper;
        }
        return base;
    }

    public BigDecimal dailyRate(BigDecimal monthlyBase) {
        return Money.divide(monthlyBase, standardWorkDays, 10);
    }

    public BigDecimal hourlyRate(BigDecimal monthlyBase) {
        return Money.divide(monthlyBase, standardWorkDays.multiply(standardDailyHours), 10);
    }
}
