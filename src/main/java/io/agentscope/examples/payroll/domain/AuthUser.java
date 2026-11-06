package io.agentscope.examples.payroll.domain;

import io.agentscope.examples.payroll.support.Permissions;
import java.util.Set;

/** 当前登录用户及其权限集合，写入 HttpSession 并返回给前端做页签/按钮控制。 */
public record AuthUser(
        long id,
        String username,
        String displayName,
        String roleCode,
        String roleName,
        Long departmentId,
        String departmentName,
        Set<String> permissions) {

    public AuthUser {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission);
    }

    /** 关闭认证时使用的内置管理员，拥有全部权限。 */
    public static AuthUser system() {
        return new AuthUser(
                0L,
                "system",
                "系统管理员",
                Permissions.ROLE_ADMIN,
                "系统管理员",
                null,
                null,
                Permissions.all());
    }
}
