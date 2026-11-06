package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 员工月度考核结果。
 *
 * <p>系数作用于当月绩效奖金：调整后绩效奖金 = 原绩效奖金 × coefficient。
 * 系数为 1 表示不调整，0 表示当月无绩效奖金。
 */
public record PerformanceReview(
        @JsonProperty("employee_id") String employeeId,
        String period,
        BigDecimal score,
        BigDecimal coefficient,
        String comment) {

    public PerformanceReview {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(period, "period");
        employeeId = employeeId.trim().toUpperCase();
        score = score == null ? BigDecimal.ZERO : score;
        coefficient = coefficient == null ? BigDecimal.ONE : coefficient;
        if (score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("考核分数必须在 0-100 之间");
        }
        if (coefficient.compareTo(BigDecimal.ZERO) < 0
                || coefficient.compareTo(new BigDecimal("2")) > 0) {
            throw new IllegalArgumentException("绩效系数必须在 0-2 之间");
        }
        score = score.setScale(2, RoundingMode.HALF_UP);
        coefficient = coefficient.setScale(4, RoundingMode.HALF_UP);
        comment = comment == null ? "" : comment.trim();
    }
}
