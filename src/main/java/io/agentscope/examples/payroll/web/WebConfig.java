package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册登录拦截器：REST 接口返回 401/403，页面跳转登录页。 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuthService authService;

    public WebConfig(AuthInterceptor authInterceptor, AuthService authService) {
        this.authInterceptor = authInterceptor;
        this.authService = authService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(new PageAuthInterceptor(authService))
                .addPathPatterns("/", "/index.html");
    }

    /** 未登录访问管理台首页时跳转登录页。 */
    private record PageAuthInterceptor(AuthService authService) implements HandlerInterceptor {

        @Override
        public boolean preHandle(
                HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            if (authService.hasSession(request)) {
                return true;
            }
            response.sendRedirect("/login.html");
            return false;
        }
    }
}
