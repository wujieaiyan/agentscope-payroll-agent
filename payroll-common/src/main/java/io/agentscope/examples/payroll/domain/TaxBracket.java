package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;

/**
 * 个人所得税累计预扣率表的一档。
 *
 * @param upperLimit 累计应纳税所得额上限，null 表示最高档无上限
 * @param rate 预扣率
 * @param quickDeduction 速算扣除数
 */
public record TaxBracket(BigDecimal upperLimit, BigDecimal rate, BigDecimal quickDeduction) {

    public TaxBracket {
        rate = Money.nz(rate);
        quickDeduction = Money.round(quickDeduction);
        if (upperLimit != null) {
            upperLimit = Money.round(upperLimit);
        }
    }

    public boolean covers(BigDecimal cumulativeTaxableIncome) {
        return upperLimit == null || cumulativeTaxableIncome.compareTo(upperLimit) <= 0;
    }
}
