package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 部门主数据；员工档案仍以部门名称关联，重命名时同步更新员工表。 */
public record Department(
        long id,
        String name,
        String description,
        boolean active,
        LocalDateTime createdAt,
        int employeeCount,
        int userCount) {
}
