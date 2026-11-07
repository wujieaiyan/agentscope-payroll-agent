package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.service.AuthService;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse login(
            @RequestBody(required = false) LoginRequest request, HttpServletRequest http) {
        if (!authService.enabled()) {
            return ApiResponse.ok(new AuthState(AuthUser.system(), false));
        }
        if (request == null || request.username() == null || request.password() == null) {
            throw new UnauthorizedException("请输入用户名和密码");
        }
        AuthUser user =
                authService
                        .authenticate(request.username(), request.password())
                        .orElseThrow(() -> new UnauthorizedException("用户名或密码错误"));
        authService.startSession(http, user);
        return ApiResponse.ok(new AuthState(user, true));
    }

    @PostMapping("/logout")
    public ApiResponse logout(HttpServletRequest request) {
        authService.logout(request);
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
        return ApiResponse.ok(Map.of("changed", true));
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword) {
    }

    public record AuthState(AuthUser user, boolean authEnabled) {
    }
}
