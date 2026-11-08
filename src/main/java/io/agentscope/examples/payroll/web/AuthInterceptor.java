package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 登录态与接口权限校验；认证关闭时直接放行。 */
public final class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS =
            Set.of("/api/auth/login", "/api/health");

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authService.enabled() || PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        AuthUser user = authService.resolve(request).orElse(null);
        if (user == null) {
            write(response, HttpStatus.UNAUTHORIZED, "未登录或会话已过期");
            return false;
        }
        RequirePermission required = requiredPermission(handler);
        if (required != null && !allowed(user, required)) {
            write(response, HttpStatus.FORBIDDEN, "无权执行该操作");
            return false;
        }
        return true;
    }

    private static RequirePermission requiredPermission(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequirePermission annotation = method.getMethodAnnotation(RequirePermission.class);
        return annotation != null
                ? annotation
                : method.getBeanType().getAnnotation(RequirePermission.class);
    }

    private static boolean allowed(AuthUser user, RequirePermission required) {
        if (required.value().length == 0) {
            return true;
        }
        if (required.any()) {
            return Arrays.stream(required.value()).anyMatch(user::can);
        }
        return Arrays.stream(required.value()).allMatch(user::can);
    }

    private static void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        AuditContext.markFailure(message, status.value());
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JsonSupport.toJson(ApiResponse.error(message)));
    }
}
