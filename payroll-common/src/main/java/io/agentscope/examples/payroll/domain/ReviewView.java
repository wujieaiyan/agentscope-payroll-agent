package io.agentscope.examples.payroll.domain;

import java.math.BigDecimal;

/** 管理台考核视图：附带员工姓名，避免前端二次匹配。 */
public record ReviewView(
        String employeeId,
        String employeeName,
        String period,
        BigDecimal score,
        BigDecimal coefficient,
        String comment) {
}
