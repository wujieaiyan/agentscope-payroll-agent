package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;

/**
 * 年度累计预扣状态，跨月递推。
 *
 * @param cumulativeGross 年初至当月的计税收入累计（应发 - 考勤扣款）
 * @param cumulativeDeductions 年初至当月的累计扣除（减除费用 + 个人社保 + 个人公积金 + 专项附加）
 * @param cumulativeTaxWithheld 年初至当月已预扣税额累计
 */
public record TaxState(
        BigDecimal cumulativeGross,
        BigDecimal cumulativeDeductions,
        BigDecimal cumulativeTaxWithheld) {

    public TaxState {
        cumulativeGross = Money.round(cumulativeGross);
        cumulativeDeductions = Money.round(cumulativeDeductions);
        cumulativeTaxWithheld = Money.round(cumulativeTaxWithheld);
    }

    public static TaxState zero() {
        return new TaxState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public TaxState next(
            BigDecimal taxableGross,
            BigDecimal deductionsThisMonth,
            BigDecimal taxWithheldThisMonth) {
        return new TaxState(
                cumulativeGross.add(taxableGross),
                cumulativeDeductions.add(deductionsThisMonth),
                cumulativeTaxWithheld.add(taxWithheldThisMonth));
    }
}
