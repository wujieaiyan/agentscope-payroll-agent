package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** 月度浮动薪酬。绩效奖金与补贴属于税前收入，计入当期应发与累计计税收入。 */
public record MonthlyVariablePay(
        @JsonProperty("employee_id") String employeeId,
        String period,
        @JsonProperty("performance_bonus") BigDecimal performanceBonus,
        @JsonProperty("other_allowance") BigDecimal otherAllowance) {

    public MonthlyVariablePay {
        performanceBonus = Money.round(Money.maxZero(performanceBonus));
        otherAllowance = Money.round(Money.maxZero(otherAllowance));
    }

    public static MonthlyVariablePay zero(String employeeId, String period) {
        return new MonthlyVariablePay(employeeId, period, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
