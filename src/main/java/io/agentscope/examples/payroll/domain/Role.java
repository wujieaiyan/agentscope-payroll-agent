package io.agentscope.examples.payroll.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/** 角色：权限码集合，内置角色不可删除、不可修改权限。 */
public record Role(
        String code,
        String name,
        String description,
        Set<String> permissions,
        boolean builtin,
        int userCount) {

    public Role {
        permissions =
                permissions == null
                        ? Set.of()
                        : Set.copyOf(new LinkedHashSet<>(permissions));
        description = description == null ? "" : description;
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
