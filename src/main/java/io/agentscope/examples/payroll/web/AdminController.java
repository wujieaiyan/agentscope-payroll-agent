package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.Role;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
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
    private final ChatSessionManager chatManager;
    private final AuditService auditService;

    public AdminController(
            H2AdminRepository repository,
            AuthService authService,
            PasswordEncoder passwordEncoder,
            ChatSessionManager chatManager,
            AuditService auditService) {
        this.repository = repository;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.chatManager = chatManager;
        this.auditService = auditService;
    }

    @GetMapping("/permissions")
    @RequirePermission(Permissions.ROLE_READ)
    public ApiResponse permissions() {
        return ApiResponse.ok(
                Permissions.labels().entrySet().stream()
                        .map(entry -> new PermissionOption(entry.getKey(), entry.getValue()))
                        .toList());
    }

    /** 清空向量索引并按 H2 历史重建；向量能力未启用时 indexed=0。 */
    @PostMapping("/vector/rebuild")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse rebuildVectors() {
        boolean enabled = chatManager.vectorMemoryReady();
        int indexed = enabled ? chatManager.rebuildVectorMemory() : 0;
        Map<String, Object> result = Map.of("enabled", enabled, "indexed", indexed);
        auditService.record("vector.rebuild", "vector_index", "payroll-chat-memory", null, result);
        return ApiResponse.ok(result);
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
        AppUser created =
                repository.createUser(
                        request.username(),
                        request.displayName(),
                        passwordEncoder.encode(request.password()),
                        request.roleCode(),
                        request.departmentId());
        auditService.record("user.create", "user", String.valueOf(created.id()), null, created);
        return ApiResponse.ok(created);
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
        AppUser before = repository.findUser(id).orElse(null);
        AppUser updated =
                repository.updateUser(
                        actingUserId,
                        id,
                        request.displayName(),
                        request.roleCode(),
                        request.departmentId(),
                        request.active());
        auditService.record("user.update", "user", String.valueOf(id), before, updated);
        return ApiResponse.ok(updated);
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
        AppUser target = repository.findUser(id).orElse(null);
        repository.updatePassword(id, passwordEncoder.encode(request.password()));
        auditService.record(
                "user.reset_password", "user", String.valueOf(id), target, Map.of("changed", true));
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
        Role created =
                repository.createRole(
                        request.code(),
                        request.name(),
                        request.description(),
                        validPermissions(request.permissions()));
        auditService.record("role.create", "role", created.code(), null, created);
        return ApiResponse.ok(created);
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
        Role updated =
                repository.updateRole(
                        role.code(),
                        request.name(),
                        request.description(),
                        validPermissions(request.permissions()));
        auditService.record("role.update", "role", updated.code(), role, updated);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/roles/{code}")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse deleteRole(@PathVariable String code) {
        Role before = repository.findRole(code).orElse(null);
        repository.deleteRole(code);
        auditService.record("role.delete", "role", code, before, Map.of("deleted", true));
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
        auditService.record(
                "department.create",
                "department",
                String.valueOf(department.id()),
                null,
                department);
        return ApiResponse.ok(department);
    }

    @PutMapping("/departments/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse updateDepartment(
            @PathVariable long id, @RequestBody(required = false) DepartmentRequest request) {
        if (request == null) {
            throw new PayrollException("请填写部门信息");
        }
        Department before = repository.findDepartment(id).orElse(null);
        Department updated =
                repository.updateDepartment(
                        id, request.name(), request.description(), request.active());
        auditService.record(
                "department.update", "department", String.valueOf(id), before, updated);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/departments/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse deleteDepartment(@PathVariable long id) {
        Department before = repository.findDepartment(id).orElse(null);
        repository.deleteDepartment(id);
        auditService.record(
                "department.delete", "department", String.valueOf(id), before,
                Map.of("deleted", true));
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
