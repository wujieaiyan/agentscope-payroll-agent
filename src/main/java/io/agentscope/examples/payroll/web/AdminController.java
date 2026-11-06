package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.Role;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户、部门与角色管理接口。所有写操作都经过权限校验与安全规则检查。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final H2AdminRepository repository;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    public AdminController(
            H2AdminRepository repository,
            AuthService authService,
            PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/permissions")
    @RequirePermission(Permissions.ROLE_READ)
    public ApiResponse permissions() {
        return ApiResponse.ok(
                Permissions.labels().entrySet().stream()
                        .map(entry -> new PermissionOption(entry.getKey(), entry.getValue()))
                        .toList());
    }

    // ---------------------------------------------------------------- 用户

    @GetMapping("/users")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse users() {
        return ApiResponse.ok(repository.users());
    }

    @PostMapping("/users")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse createUser(@RequestBody(required = false) UserCreateRequest request) {
        if (request == null) {
            throw new PayrollException("请填写用户信息");
        }
        H2AdminRepository.validatePassword(request.password());
        return ApiResponse.ok(
                repository.createUser(
                        request.username(),
                        request.displayName(),
                        passwordEncoder.encode(request.password()),
                        request.roleCode(),
                        request.departmentId()));
    }

    @PutMapping("/users/{id}")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse updateUser(
            @PathVariable long id,
            @RequestBody(required = false) UserUpdateRequest request,
            HttpServletRequest http) {
        if (request == null) {
            throw new PayrollException("请填写用户信息");
        }
        long actingUserId = authService.currentUser(http).id();
        return ApiResponse.ok(
                repository.updateUser(
                        actingUserId,
                        id,
                        request.displayName(),
                        request.roleCode(),
                        request.departmentId(),
                        request.active()));
    }

    @PostMapping("/users/{id}/password")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse resetPassword(
            @PathVariable long id, @RequestBody(required = false) PasswordResetRequest request) {
        if (request == null) {
            throw new PayrollException("请填写新密码");
        }
        H2AdminRepository.validatePassword(request.password());
        repository.findAccount(id).orElseThrow(() -> new PayrollException("用户不存在：" + id));
        repository.updatePassword(id, passwordEncoder.encode(request.password()));
        return ApiResponse.ok(Map.of("changed", true));
    }

    // ---------------------------------------------------------------- 角色

    @GetMapping("/roles")
    @RequirePermission(Permissions.ROLE_READ)
    public ApiResponse roles() {
        return ApiResponse.ok(repository.roles());
    }

    @PostMapping("/roles")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse createRole(@RequestBody(required = false) RoleRequest request) {
        if (request == null) {
            throw new PayrollException("请填写角色信息");
        }
        return ApiResponse.ok(
                repository.createRole(
                        request.code(),
                        request.name(),
                        request.description(),
                        validPermissions(request.permissions())));
    }

    @PutMapping("/roles/{code}")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse updateRole(
            @PathVariable String code, @RequestBody(required = false) RoleRequest request) {
        if (request == null) {
            throw new PayrollException("请填写角色信息");
        }
        Role role =
                repository
                        .findRole(code)
                        .orElseThrow(() -> new PayrollException("角色不存在：" + code));
        return ApiResponse.ok(
                repository.updateRole(
                        role.code(),
                        request.name(),
                        request.description(),
                        validPermissions(request.permissions())));
    }

    @DeleteMapping("/roles/{code}")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse deleteRole(@PathVariable String code) {
        repository.deleteRole(code);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    // ---------------------------------------------------------------- 部门

    @GetMapping("/departments")
    @RequirePermission(Permissions.DEPARTMENT_READ)
    public ApiResponse departments(
            @RequestParam(name = "include_inactive", defaultValue = "false")
                    boolean includeInactive) {
        return ApiResponse.ok(repository.departments(includeInactive));
    }

    @PostMapping("/departments")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse createDepartment(
            @RequestBody(required = false) DepartmentRequest request) {
        if (request == null) {
            throw new PayrollException("请填写部门信息");
        }
        Department department =
                repository.createDepartment(request.name(), request.description());
        return ApiResponse.ok(department);
    }

    @PutMapping("/departments/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse updateDepartment(
            @PathVariable long id, @RequestBody(required = false) DepartmentRequest request) {
        if (request == null) {
            throw new PayrollException("请填写部门信息");
        }
        return ApiResponse.ok(
                repository.updateDepartment(
                        id, request.name(), request.description(), request.active()));
    }

    @DeleteMapping("/departments/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse deleteDepartment(@PathVariable long id) {
        repository.deleteDepartment(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    private static Set<String> validPermissions(List<String> permissions) {
        Set<String> result = new LinkedHashSet<>();
        if (permissions == null) {
            return result;
        }
        for (String permission : permissions) {
            if (!Permissions.known(permission)) {
                throw new PayrollException("未知权限：" + permission);
            }
            result.add(permission);
        }
        return result;
    }

    public record PermissionOption(String code, String label) {
    }

    public record UserCreateRequest(
            String username,
            String displayName,
            String password,
            String roleCode,
            Long departmentId) {
    }

    public record UserUpdateRequest(
            String displayName,
            String roleCode,
            Long departmentId,
            boolean active) {
    }

    public record PasswordResetRequest(String password) {
    }

    public record RoleRequest(
            String code, String name, String description, List<String> permissions) {
    }

    public record DepartmentRequest(String name, String description, boolean active) {
    }
}
