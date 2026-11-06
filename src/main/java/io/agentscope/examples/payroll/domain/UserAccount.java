package io.agentscope.examples.payroll.domain;

/** 认证用的用户账号，包含密码散列，禁止直接序列化到响应。 */
public record UserAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String roleCode,
        Long departmentId,
        boolean active) {
}
