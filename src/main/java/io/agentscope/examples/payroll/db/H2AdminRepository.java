package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.Role;
import io.agentscope.examples.payroll.domain.UserAccount;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.support.Permissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 用户、角色、部门与登录认证的 H2 仓储。 */
public final class H2AdminRepository {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.-]{3,32}");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final int MIN_PASSWORD = 6;

    private static final String ROLE_SELECT =
            """
            SELECT r.code, r.name, r.description, r.permissions_json, r.builtin,
                   (SELECT COUNT(*) FROM app_users u WHERE u.role_code = r.code) AS user_count
            FROM roles r
            """;

    private static final String USER_SELECT =
            """
            SELECT u.user_id, u.username, u.display_name, u.role_code, r.name AS role_name,
                   u.department_id, d.name AS department_name, u.active,
                   u.created_at, u.updated_at, u.last_login_at
            FROM app_users u
            LEFT JOIN roles r ON r.code = u.role_code
            LEFT JOIN departments d ON d.department_id = u.department_id
            """;

    private static final String DEPARTMENT_SELECT =
            """
            SELECT d.department_id, d.name, d.description, d.active, d.created_at,
                   (SELECT COUNT(*) FROM employees e WHERE e.department = d.name)
                       AS employee_count,
                   (SELECT COUNT(*) FROM app_users u WHERE u.department_id = d.department_id)
                       AS user_count
            FROM departments d
            """;

    private final PayrollDatabase database;

    public H2AdminRepository(PayrollDatabase database) {
        this.database = database;
    }

    // ---------------------------------------------------------------- 角色

    public List<Role> roles() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(ROLE_SELECT + " ORDER BY r.builtin DESC, r.code");
                ResultSet rs = statement.executeQuery()) {
            List<Role> roles = new ArrayList<>();
            while (rs.next()) {
                roles.add(mapRole(rs));
            }
            return List.copyOf(roles);
        } catch (SQLException e) {
            throw new IllegalStateException("查询角色失败：" + e.getMessage(), e);
        }
    }

    public Optional<Role> findRole(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(ROLE_SELECT + " WHERE r.code = ?")) {
            statement.setString(1, code.trim().toUpperCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRole(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询角色失败：" + e.getMessage(), e);
        }
    }

    public Role createRole(String code, String name, String description, Set<String> permissions) {
        String normalized = normalizeRoleCode(code);
        if (findRole(normalized).isPresent()) {
            throw new PayrollException("角色编码已存在：" + normalized);
        }
        String sql =
                """
                INSERT INTO roles (code, name, description, permissions_json, builtin, created_at)
                VALUES (?, ?, ?, ?, FALSE, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, requireText(name, "角色名称", 64));
            statement.setString(3, optionalText(description, 200));
            statement.setString(4, JsonSupport.toJson(Permissions.sanitize(permissions)));
            statement.setObject(5, LocalDateTime.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("创建角色失败：" + e.getMessage(), e);
        }
        return findRole(normalized).orElseThrow(() -> new IllegalStateException("角色保存后无法读取"));
    }

    public Role updateRole(String code, String name, String description, Set<String> permissions) {
        Role existing = findRole(code).orElseThrow(() -> new PayrollException("角色不存在：" + code));
        if (existing.builtin()) {
            throw new PayrollException("内置角色不允许修改");
        }
        String sql =
                """
                UPDATE roles SET name = ?, description = ?, permissions_json = ?
                WHERE code = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireText(name, "角色名称", 64));
            statement.setString(2, optionalText(description, 200));
            statement.setString(3, JsonSupport.toJson(Permissions.sanitize(permissions)));
            statement.setString(4, existing.code());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新角色失败：" + e.getMessage(), e);
        }
        return findRole(existing.code()).orElseThrow();
    }

    public void deleteRole(String code) {
        Role role = findRole(code).orElseThrow(() -> new PayrollException("角色不存在：" + code));
        if (role.builtin()) {
            throw new PayrollException("内置角色不允许删除");
        }
        if (role.userCount() > 0) {
            throw new PayrollException("仍有用户使用角色 " + role.code() + "，请先调整用户角色");
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM roles WHERE code = ?")) {
            statement.setString(1, role.code());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除角色失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 部门

    public List<Department> departments(boolean includeInactive) {
        String sql =
                DEPARTMENT_SELECT
                        + (includeInactive ? "" : " WHERE d.active = TRUE")
                        + " ORDER BY d.department_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<Department> departments = new ArrayList<>();
            while (rs.next()) {
                departments.add(mapDepartment(rs));
            }
            return List.copyOf(departments);
        } catch (SQLException e) {
            throw new IllegalStateException("查询部门失败：" + e.getMessage(), e);
        }
    }

    public Optional<Department> findDepartment(long id) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                DEPARTMENT_SELECT + " WHERE d.department_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapDepartment(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询部门失败：" + e.getMessage(), e);
        }
    }

    public Department createDepartment(String name, String description) {
        String normalized = requireText(name, "部门名称", 64);
        if (departmentNameExists(normalized, null)) {
            throw new PayrollException("部门已存在：" + normalized);
        }
        String sql =
                """
                INSERT INTO departments (name, description, active, created_at)
                VALUES (?, ?, TRUE, ?)
                """;
        long id;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalized);
            statement.setString(2, optionalText(description, 200));
            statement.setObject(3, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建部门失败：" + e.getMessage(), e);
        }
        return findDepartment(id).orElseThrow();
    }

    public Department updateDepartment(
            long id, String name, String description, boolean active) {
        Department existing =
                findDepartment(id).orElseThrow(() -> new PayrollException("部门不存在：" + id));
        String normalized = requireText(name, "部门名称", 64);
        if (departmentNameExists(normalized, id)) {
            throw new PayrollException("部门已存在：" + normalized);
        }
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                if (!existing.name().equals(normalized)) {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "UPDATE employees SET department = ? WHERE department = ?")) {
                        statement.setString(1, normalized);
                        statement.setString(2, existing.name());
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE departments SET name = ?, description = ?, active = ?
                                WHERE department_id = ?
                                """)) {
                    statement.setString(1, normalized);
                    statement.setString(2, optionalText(description, 200));
                    statement.setBoolean(3, active);
                    statement.setLong(4, id);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新部门失败：" + e.getMessage(), e);
        }
        return findDepartment(id).orElseThrow();
    }

    public void deleteDepartment(long id) {
        Department department =
                findDepartment(id).orElseThrow(() -> new PayrollException("部门不存在：" + id));
        if (department.userCount() > 0) {
            throw new PayrollException("仍有用户归属部门 " + department.name() + "，请先调整用户");
        }
        if (department.employeeCount() > 0) {
            throw new PayrollException("仍有员工归属部门 " + department.name() + "，请先调整员工档案");
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM departments WHERE department_id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除部门失败：" + e.getMessage(), e);
        }
    }

    private boolean departmentNameExists(String name, Long excludeId) {
        String sql =
                excludeId == null
                        ? "SELECT 1 FROM departments WHERE name = ?"
                        : "SELECT 1 FROM departments WHERE name = ? AND department_id <> ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            if (excludeId != null) {
                statement.setLong(2, excludeId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验部门名称失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 用户

    public List<AppUser> users() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(USER_SELECT + " ORDER BY u.user_id");
                ResultSet rs = statement.executeQuery()) {
            List<AppUser> users = new ArrayList<>();
            while (rs.next()) {
                users.add(mapUser(rs));
            }
            return List.copyOf(users);
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败：" + e.getMessage(), e);
        }
    }

    public Optional<AppUser> findUser(long id) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(USER_SELECT + " WHERE u.user_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败：" + e.getMessage(), e);
        }
    }

    public Optional<UserAccount> findAccount(long id) {
        String sql =
                """
                SELECT user_id, username, display_name, password_hash, role_code,
                       department_id, active
                FROM app_users WHERE user_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapAccount(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败：" + e.getMessage(), e);
        }
    }

    public Optional<UserAccount> findAccountByUsername(String username) {
        String normalized = normalizeUsername(username);
        String sql =
                """
                SELECT user_id, username, display_name, password_hash, role_code,
                       department_id, active
                FROM app_users WHERE username = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapAccount(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败：" + e.getMessage(), e);
        }
    }

    public Optional<AuthUser> findAuthUser(long id) {
        String sql =
                """
                SELECT u.user_id, u.username, u.display_name, u.role_code, r.name AS role_name,
                       u.department_id, d.name AS department_name, r.permissions_json
                FROM app_users u
                LEFT JOIN roles r ON r.code = u.role_code
                LEFT JOIN departments d ON d.department_id = u.department_id
                WHERE u.user_id = ? AND u.active = TRUE
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new AuthUser(
                                rs.getLong("user_id"),
                                rs.getString("username"),
                                rs.getString("display_name"),
                                rs.getString("role_code"),
                                rs.getString("role_name"),
                                nullableLong(rs, "department_id"),
                                rs.getString("department_name"),
                                permissions(rs.getString("permissions_json"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户权限失败：" + e.getMessage(), e);
        }
    }

    public AppUser createUser(
            String username,
            String displayName,
            String passwordHash,
            String roleCode,
            Long departmentId) {
        String normalized = normalizeUsername(username);
        if (findAccountByUsername(normalized).isPresent()) {
            throw new PayrollException("用户名已存在：" + normalized);
        }
        requireRole(roleCode);
        requireDepartment(departmentId);
        String sql =
                """
                INSERT INTO app_users
                    (username, display_name, password_hash, role_code, department_id,
                     active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                """;
        long id;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalized);
            statement.setString(2, requireText(displayName, "姓名", 64));
            statement.setString(3, passwordHash);
            statement.setString(4, roleCode);
            if (departmentId == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, departmentId);
            }
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(6, now);
            statement.setObject(7, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建用户失败：" + e.getMessage(), e);
        }
        return findUser(id).orElseThrow();
    }

    public AppUser updateUser(
            long actingUserId,
            long id,
            String displayName,
            String roleCode,
            Long departmentId,
            boolean active) {
        UserAccount target =
                findAccount(id).orElseThrow(() -> new PayrollException("用户不存在：" + id));
        if (!active && id == actingUserId) {
            throw new PayrollException("不能停用当前登录账号");
        }
        if (!target.roleCode().equals(roleCode) && id == actingUserId) {
            throw new PayrollException("不能修改当前登录账号的角色");
        }
        requireRole(roleCode);
        requireDepartment(departmentId);
        boolean losesAdmin =
                Permissions.ROLE_ADMIN.equals(target.roleCode())
                        && (!active || !Permissions.ROLE_ADMIN.equals(roleCode));
        if (losesAdmin && target.active() && countActiveAdmins() <= 1) {
            throw new PayrollException("系统至少需要保留一名启用状态的管理员");
        }
        String sql =
                """
                UPDATE app_users
                SET display_name = ?, role_code = ?, department_id = ?, active = ?, updated_at = ?
                WHERE user_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireText(displayName, "姓名", 64));
            statement.setString(2, roleCode);
            if (departmentId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, departmentId);
            }
            statement.setBoolean(4, active);
            statement.setObject(5, LocalDateTime.now());
            statement.setLong(6, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新用户失败：" + e.getMessage(), e);
        }
        return findUser(id).orElseThrow();
    }

    public void updatePassword(long id, String passwordHash) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE app_users SET password_hash = ?, updated_at = ?
                                WHERE user_id = ?
                                """)) {
            statement.setString(1, passwordHash);
            statement.setObject(2, LocalDateTime.now());
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新密码失败：" + e.getMessage(), e);
        }
    }

    public void touchLastLogin(long id) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE app_users SET last_login_at = ? WHERE user_id = ?")) {
            statement.setObject(1, LocalDateTime.now());
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新登录时间失败：" + e.getMessage(), e);
        }
    }

    private int countActiveAdmins() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM app_users
                                WHERE role_code = ? AND active = TRUE
                                """)) {
            statement.setString(1, Permissions.ROLE_ADMIN);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计管理员失败：" + e.getMessage(), e);
        }
    }

    /** 首次启动时创建 admin 账号；已存在任何管理员时不覆盖。 */
    public boolean ensureAdminUser(String passwordHash) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT 1 FROM app_users WHERE role_code = ? LIMIT 1")) {
            statement.setString(1, Permissions.ROLE_ADMIN);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查管理员账号失败：" + e.getMessage(), e);
        }
        if (findAccountByUsername("admin").isPresent()) {
            return false;
        }
        createUser("admin", "系统管理员", passwordHash, Permissions.ROLE_ADMIN, null);
        return true;
    }

    private void requireRole(String roleCode) {
        if (findRole(roleCode).isEmpty()) {
            throw new PayrollException("角色不存在：" + roleCode);
        }
    }

    private void requireDepartment(Long departmentId) {
        if (departmentId == null) {
            return;
        }
        Department department =
                findDepartment(departmentId)
                        .orElseThrow(() -> new PayrollException("部门不存在：" + departmentId));
        if (!department.active()) {
            throw new PayrollException("部门已停用：" + department.name());
        }
    }

    public static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(value).matches()) {
            throw new PayrollException("用户名需为 3-32 位小写字母、数字或 _ . -");
        }
        return value;
    }

    public static String validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new PayrollException("密码至少 " + MIN_PASSWORD + " 位");
        }
        if (password.length() > 72) {
            throw new PayrollException("密码不能超过 72 位");
        }
        return password;
    }

    private static String normalizeRoleCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_CODE.matcher(value).matches()) {
            throw new PayrollException("角色编码需为 2-32 位大写字母、数字或下划线");
        }
        return value;
    }

    private static String requireText(String value, String label, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new PayrollException(label + "不能为空");
        }
        if (text.length() > max) {
            throw new PayrollException(label + "不能超过 " + max + " 个字符");
        }
        return text;
    }

    private static String optionalText(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) {
            throw new PayrollException("说明不能超过 " + max + " 个字符");
        }
        return text;
    }

    private static Role mapRole(ResultSet rs) throws SQLException {
        return new Role(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                permissions(rs.getString("permissions_json")),
                rs.getBoolean("builtin"),
                rs.getInt("user_count"));
    }

    private static Department mapDepartment(ResultSet rs) throws SQLException {
        return new Department(
                rs.getLong("department_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("active"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getInt("employee_count"),
                rs.getInt("user_count"));
    }

    private static AppUser mapUser(ResultSet rs) throws SQLException {
        return new AppUser(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role_code"),
                rs.getString("role_name"),
                nullableLong(rs, "department_id"),
                rs.getString("department_name"),
                rs.getBoolean("active"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("last_login_at", LocalDateTime.class));
    }

    private static UserAccount mapAccount(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role_code"),
                nullableLong(rs, "department_id"),
                rs.getBoolean("active"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Set<String> permissions(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        String[] values = JsonSupport.fromJson(json, String[].class);
        return Permissions.sanitize(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
