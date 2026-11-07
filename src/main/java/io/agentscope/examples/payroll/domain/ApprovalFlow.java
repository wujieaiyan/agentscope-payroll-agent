package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 审批链配置：按部门范围与实发金额区间匹配，同一范围最多一条生效流程。
 *
 * <p>部门专属流程优先于全局流程；金额区间用于在全局流程内区分不同审批要求。
 */
public record ApprovalFlow(
        long flowId,
        String name,
        String scopeType,
        String scopeValue,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int priority,
        boolean enabled,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String GLOBAL = "GLOBAL";
    public static final String DEPARTMENT = "DEPARTMENT";

    public boolean covers(String scope, BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (minAmount != null && amount.compareTo(minAmount) < 0) {
            return false;
        }
        if (maxAmount != null && amount.compareTo(maxAmount) > 0) {
            return false;
        }
        if (GLOBAL.equals(scopeType)) {
            return true;
        }
        return scope != null
                && scopeValue != null
                && scopeValue.toLowerCase(Locale.ROOT)
                        .equals(scope.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isDepartmentScoped() {
        return DEPARTMENT.equals(scopeType);
    }

    public String scopeLabel() {
        return GLOBAL.equals(scopeType) ? "全部部门" : scopeValue;
    }

    public String amountRangeLabel() {
        if (maxAmount == null) {
            return "≥ " + plain(minAmount);
        }
        return plain(minAmount) + " ~ " + plain(maxAmount);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
