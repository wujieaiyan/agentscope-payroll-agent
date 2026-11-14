package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * 金额与比率运算的统一入口。
 *
 * <p>薪酬核算的所有中间结果都必须经过这里收敛精度，禁止各模块自行 setScale，
 * 否则会出现“分位漂移”导致工资条与银行代发金额不一致。
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() {
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static BigDecimal round(BigDecimal value) {
        return nz(value).setScale(SCALE, ROUNDING);
    }

    /** 按比率计算并保留两位小数，例如社保 37500 * 8%。 */
    public static BigDecimal rateOf(BigDecimal base, BigDecimal rate) {
        return round(nz(base).multiply(nz(rate)));
    }

    /** 高精度除法，用于日薪、时薪等中间量，最终展示前再 round。 */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        if (divisor == null || divisor.signum() == 0) {
            throw new ArithmeticException("divisor must not be zero");
        }
        return nz(dividend).divide(divisor, scale, ROUNDING);
    }

    public static BigDecimal maxZero(BigDecimal value) {
        BigDecimal v = nz(value);
        return v.signum() < 0 ? ZERO : v;
    }

    public static String format(BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.CHINA);
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        return format.format(round(value));
    }
}
