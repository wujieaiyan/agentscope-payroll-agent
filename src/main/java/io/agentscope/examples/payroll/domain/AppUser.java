package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/** 对外展示的用户视图，不包含密码散列。 */
public record AppUser(
        long id,
        String username,
        String displayName,
        String roleCode,
        String roleName,
        Long departmentId,
        String departmentName,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastLoginAt) {
}
