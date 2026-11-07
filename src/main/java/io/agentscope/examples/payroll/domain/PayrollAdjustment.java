package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;

/**
 * 出账前的人工微调，仅作用于目标月份。
 *
 * <p>字段为 null 表示沿用原值；非 null 表示该金额为最终值（不再叠加绩效系数）。
 */
public record PayrollAdjustment(BigDecimal performanceBonus, BigDecimal otherAllowance) {

    public PayrollAdjustment {
        performanceBonus = performanceBonus == null ? null : Money.round(Money.maxZero(performanceBonus));
        otherAllowance = otherAllowance == null ? null : Money.round(Money.maxZero(otherAllowance));
    }

    public boolean isEmpty() {
        return performanceBonus == null && otherAllowance == null;
    }
}
