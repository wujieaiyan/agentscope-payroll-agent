package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** 个税累计预扣明细，用于向 HR 解释“为什么本月个税变了”。 */
public record TaxDetail(
        @JsonProperty("cumulative_gross") BigDecimal cumulativeGross,
        @JsonProperty("cumulative_deductions") BigDecimal cumulativeDeductions,
        @JsonProperty("cumulative_taxable_income") BigDecimal cumulativeTaxableIncome,
        @JsonProperty("applicable_rate") BigDecimal applicableRate,
        @JsonProperty("quick_deduction") BigDecimal quickDeduction,
        @JsonProperty("cumulative_tax") BigDecimal cumulativeTax,
        @JsonProperty("tax_this_month") BigDecimal taxThisMonth,
        String explanation) {
}
