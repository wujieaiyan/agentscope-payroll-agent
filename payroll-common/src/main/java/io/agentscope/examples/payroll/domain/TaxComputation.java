package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;

/** 按累计应纳税所得额命中的税率档位及税额。 */
public record TaxComputation(BigDecimal tax, BigDecimal rate, BigDecimal quickDeduction) {
}
