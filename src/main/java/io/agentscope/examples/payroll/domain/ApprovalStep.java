package io.agentscope.examples.payroll.domain;

import io.agentscope.examples.payroll.support.Permissions;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * 审批工作流中的一个节点：按顺序执行，审批人可以是角色、指定用户或直接声明权限。
 *
 * <p>金额区间为空表示该节点对所有金额生效；提交时按批次实发合计过滤并快照到批次上，
 * 之后调整审批链配置不会影响已提交批次的执行路径。
 */
public record ApprovalStep(
        int order,
        String name,
        String approverType,
        String approverValue,
        BigDecimal minAmount,
        BigDecimal maxAmount) {

    public static final String ROLE = "ROLE";
    public static final String USER = "USER";
    public static final String PERMISSION = "PERMISSION";

    public ApprovalStep {
        name = name == null ? "" : name.trim();
        approverType = approverType == null ? ROLE : approverType.trim().toUpperCase(Locale.ROOT);
        approverValue = approverValue == null ? "" : approverValue.trim();
    }

    /** 未配置节点时的默认单级审批：任何拥有审批权限的用户均可处理。 */
    public static ApprovalStep permissionFallback() {
        return new ApprovalStep(
                1, "审批", PERMISSION, Permissions.PAYROLL_APPROVE, null, null);
    }

    public boolean covers(BigDecimal amount) {
        if (amount == null) {
            return minAmount == null && maxAmount == null;
        }
        if (minAmount != null && amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }

    public boolean matches(AuthUser user) {
        if (user == null) {
            return false;
        }
        return switch (approverType) {
            case USER -> user.username() != null
                    && approverValue.equalsIgnoreCase(user.username());
            case PERMISSION -> user.can(approverValue);
            case ROLE -> user.roleCode() != null
                    && approverValue.equalsIgnoreCase(user.roleCode());
            default -> false;
        };
    }

    public String approverLabel() {
        return switch (approverType) {
            case USER -> "用户 " + approverValue;
            case PERMISSION -> "权限 " + approverValue;
            default -> "角色 " + approverValue;
        };
    }

    public String amountLabel() {
        if (minAmount == null && maxAmount == null) {
            return "全部金额";
        }
        String min = minAmount == null ? "0" : minAmount.stripTrailingZeros().toPlainString();
        if (maxAmount == null) {
            return "≥ " + min;
        }
        return min + " ~ " + maxAmount.stripTrailingZeros().toPlainString();
    }
}
