package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录、退出、当前用户与修改密码。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public ApiResponse login(
            @RequestBody(required = false) LoginRequest request, HttpServletRequest http) {
        if (!authService.enabled()) {
            AuthUser system = AuthUser.system();
            AuditContext.setActor(system);
            auditService.record(
                    "auth.login", "user", "system", null, Map.of("auth_enabled", false));
            return ApiResponse.ok(new AuthState(system, false));
        }
        if (request == null || request.username() == null || request.password() == null) {
            AuditContext.setActorName("anonymous");
            auditService.recordFailure("auth.login", "user", "anonymous", 401, "请输入用户名和密码");
            throw new UnauthorizedException("请输入用户名和密码");
        }
        AuthUser user =
                authService.authenticate(request.username(), request.password()).orElse(null);
        if (user == null) {
            String attempted = request.username().trim();
            AuditContext.setActorName(attempted);
            auditService.recordFailure("auth.login", "user", attempted, 401, "用户名或密码错误");
            throw new UnauthorizedException("用户名或密码错误");
        }
        authService.startSession(http, user);
        AuditContext.setActor(user);
        auditService.record(
                "auth.login",
                "user",
                String.valueOf(user.id()),
                null,
                Map.of("username", user.username(), "role_code", user.roleCode()));
        return ApiResponse.ok(new AuthState(user, true));
    }

    @PostMapping("/logout")
    public ApiResponse logout(HttpServletRequest request) {
        AuthUser actor = authService.resolve(request).orElse(null);
        authService.logout(request);
        auditService.record(
                "auth.logout",
                "user",
                actor == null ? null : String.valueOf(actor.id()),
                null,
                Map.of("logged_out", true));
        return ApiResponse.ok(Map.of("logged_out", true));
    }

    @GetMapping("/me")
    public ApiResponse me(HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(new AuthState(user, authService.enabled()));
    }

    @PostMapping("/password")
    public ApiResponse changePassword(
            @RequestBody(required = false) PasswordChangeRequest request,
            HttpServletRequest http) {
        AuthUser user = authService.currentUser(http);
        if (request == null) {
            throw new IllegalArgumentException("请提供原密码与新密码");
        }
        authService.changePassword(user.id(), request.oldPassword(), request.newPassword());
        auditService.record(
                "auth.password",
                "user",
                String.valueOf(user.id()),
                null,
                Map.of("changed", true));
        return ApiResponse.ok(Map.of("changed", true));
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword) {
    }

    public record AuthState(AuthUser user, boolean authEnabled) {
    }
}
