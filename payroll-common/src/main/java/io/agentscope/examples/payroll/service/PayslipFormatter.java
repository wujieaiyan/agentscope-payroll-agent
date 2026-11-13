package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.Money;
import io.agentscope.examples.payroll.domain.PayrollResult;
import java.math.BigDecimal;
import java.util.List;

/** 将核算结果渲染为可读工资条；仅做展示，不参与任何计算。 */
public final class PayslipFormatter {

    private static final String SEPARATOR = "=".repeat(58);
    private static final String SUB_SEPARATOR = "-".repeat(58);

    private PayslipFormatter() {
    }

    public static String format(PayrollResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(SEPARATOR).append(System.lineSeparator());
        sb.append("工资条  ").append(result.period()).append(System.lineSeparator());
        sb.append("员工：")
                .append(result.employeeName())
                .append("（")
                .append(result.employeeId())
                .append("）  部门：")
                .append(result.department())
                .append(System.lineSeparator());
        sb.append(SUB_SEPARATOR).append(System.lineSeparator());
        sb.append("应发项目").append(System.lineSeparator());
        appendAmount(sb, "基本工资", result.baseSalary());
        appendAmount(sb, "岗位津贴", result.positionAllowance());
        appendAmount(sb, "加班费", result.overtimePay());
        appendAmount(sb, "绩效奖金", result.performanceBonus());
        appendAmount(sb, "其他补贴", result.otherAllowance());
        appendAmount(sb, "应发合计", result.grossPay());
        sb.append(SUB_SEPARATOR).append(System.lineSeparator());
        sb.append("扣款项目").append(System.lineSeparator());
        appendAmount(sb, "考勤扣款", result.attendanceDeduction());
        appendAmount(sb, "个人社保", result.socialInsuranceEmployee());
        appendAmount(sb, "个人公积金", result.housingFundEmployee());
        appendAmount(sb, "个人所得税", result.tax().taxThisMonth());
        appendAmount(sb, "扣款合计", result.totalDeductions());
        sb.append(SUB_SEPARATOR).append(System.lineSeparator());
        appendAmount(sb, "实发工资", result.netPay());
        sb.append("企业成本：")
                .append(Money.format(result.employerCost()))
                .append(" 元（含企业社保/公积金 ")
                .append(Money.format(
                        result.socialInsuranceEmployer().add(result.housingFundEmployer())))
                .append(" 元）")
                .append(System.lineSeparator());
        sb.append("个税说明：").append(result.tax().explanation()).append(System.lineSeparator());
        appendWarnings(sb, result.warnings());
        sb.append(SEPARATOR).append(System.lineSeparator());
        return sb.toString();
    }

    private static void appendAmount(StringBuilder sb, String label, BigDecimal amount) {
        sb.append(String.format("  %-20s%16s%n", label, Money.format(amount)));
    }

    private static void appendWarnings(StringBuilder sb, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        sb.append("风险提示：").append(System.lineSeparator());
        for (String warning : warnings) {
            sb.append("  - ").append(warning).append(System.lineSeparator());
        }
    }
}
