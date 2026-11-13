package io.agentscope.examples.payroll.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 按节点角色收敛入口：runtime 只接受内部接口，web 不暴露内部接口，all 保持现状。
 */
public final class NodeRoleFilter extends OncePerRequestFilter {

    private final String role;

    public NodeRoleFilter(String role) {
        this.role = role == null ? "all" : role.trim().toLowerCase();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean internal = path.startsWith("/internal/");
        if ("runtime".equals(role)) {
            if (!internal && !path.equals("/actuator/health")) {
                reject(response);
                return;
            }
        } else if ("web".equals(role) && internal) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream()
                .write("{\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
    }
}
