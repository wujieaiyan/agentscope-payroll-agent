package io.agentscope.examples.payroll.support;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 管理台权限码与内置角色定义。角色权限以权限码集合存储，接口按需声明。 */
public final class Permissions {

    public static final String EMPLOYEE_READ = "employee:read";
    public static final String EMPLOYEE_WRITE = "employee:write";
    public static final String REVIEW_READ = "review:read";
    public static final String REVIEW_WRITE = "review:write";
    public static final String POLICY_READ = "policy:read";
    public static final String POLICY_WRITE = "policy:write";
    public static final String ATTENDANCE_READ = "attendance:read";
    public static final String ATTENDANCE_WRITE = "attendance:write";
    public static final String PAYROLL_READ = "payroll:read";
    public static final String PAYROLL_WRITE = "payroll:write";
    public static final String IMPORT_USE = "import:use";
    public static final String CHAT_USE = "chat:use";
    public static final String DEPARTMENT_READ = "department:read";
    public static final String DEPARTMENT_MANAGE = "department:manage";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_MANAGE = "role:manage";
    public static final String USER_MANAGE = "user:manage";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_HR = "HR";
    public static final String ROLE_VIEWER = "VIEWER";

    private static final Map<String, String> LABELS = labels();
    private static final Set<String> ALL = Set.copyOf(LABELS.keySet());

    private static final Set<String> HR =
            Set.of(
                    EMPLOYEE_READ,
                    EMPLOYEE_WRITE,
                    REVIEW_READ,
                    REVIEW_WRITE,
                    POLICY_READ,
                    ATTENDANCE_READ,
                    ATTENDANCE_WRITE,
                    PAYROLL_READ,
                    PAYROLL_WRITE,
                    IMPORT_USE,
                    CHAT_USE,
                    DEPARTMENT_READ,
                    ROLE_READ);

    private static final Set<String> VIEWER =
            Set.of(
                    EMPLOYEE_READ,
                    REVIEW_READ,
                    POLICY_READ,
                    ATTENDANCE_READ,
                    PAYROLL_READ,
                    DEPARTMENT_READ,
                    ROLE_READ,
                    CHAT_USE);

    private Permissions() {
    }

    public static Set<String> all() {
        return ALL;
    }

    public static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(EMPLOYEE_READ, "查看员工");
        labels.put(EMPLOYEE_WRITE, "维护员工");
        labels.put(REVIEW_READ, "查看考核");
        labels.put(REVIEW_WRITE, "维护考核");
        labels.put(POLICY_READ, "查看城市政策");
        labels.put(POLICY_WRITE, "维护城市政策");
        labels.put(ATTENDANCE_READ, "查看考勤");
        labels.put(ATTENDANCE_WRITE, "维护考勤");
        labels.put(PAYROLL_READ, "查看工资批次");
        labels.put(PAYROLL_WRITE, "核算/出账/发送");
        labels.put(IMPORT_USE, "Excel 导入");
        labels.put(CHAT_USE, "使用智能问答");
        labels.put(DEPARTMENT_READ, "查看部门");
        labels.put(DEPARTMENT_MANAGE, "管理部门");
        labels.put(ROLE_READ, "查看角色");
        labels.put(ROLE_MANAGE, "管理角色");
        labels.put(USER_MANAGE, "管理用户");
        return labels;
    }

    public static String label(String code) {
        return LABELS.getOrDefault(code, code);
    }

    public static boolean known(String code) {
        return code != null && LABELS.containsKey(code);
    }

    public static int builtinRank(String code) {
        return switch (code) {
            case ROLE_ADMIN -> 0;
            case ROLE_HR -> 1;
            case ROLE_VIEWER -> 2;
            default -> 99;
        };
    }

    public static Map<String, Set<String>> builtinRolePermissions() {
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        roles.put(ROLE_ADMIN, ALL);
        roles.put(ROLE_HR, HR);
        roles.put(ROLE_VIEWER, VIEWER);
        return roles;
    }

    public static Set<String> sanitize(Set<String> permissions) {
        Set<String> result = new LinkedHashSet<>();
        if (permissions != null) {
            permissions.stream()
                    .filter(Permissions::known)
                    .sorted((a, b) -> Integer.compare(index(a), index(b)))
                    .forEach(result::add);
        }
        return result;
    }

    private static List<String> order() {
        return List.copyOf(LABELS.keySet());
    }

    private static int index(String code) {
        int index = order().indexOf(code);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
