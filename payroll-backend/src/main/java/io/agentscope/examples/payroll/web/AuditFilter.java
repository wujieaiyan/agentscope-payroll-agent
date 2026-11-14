package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.AuditContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 写请求审计：缓存小体积 JSON 请求体，请求结束后补记通用事件。
 *
 * <p>控制器已经显式写入业务事件时不会重复记录；401/403 等拦截器拒绝的请求
 * 也会留下通用事件，便于排查越权尝试。
 */
public final class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);
    private static final int MAX_BODY_BYTES = 32 * 1024;

    private final AuditService auditService;
    private final AuthService authService;

    public AuditFilter(AuditService auditService, AuthService authService) {
        this.auditService = auditService;
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean write =
                "POST".equals(method)
                        || "PUT".equals(method)
                        || "DELETE".equals(method)
                        || "PATCH".equals(method);
        return !write || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CachedBodyRequest wrapped = CachedBodyRequest.wrap(request);
        AuditContext.begin(actor(request), clientIp(request), header(request, "User-Agent"));
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            try {
                AuditContext.State context = AuditContext.current();
                auditService.recordRequest(
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        wrapped.cachedBody(),
                        response.getStatus(),
                        context == null ? null : context.failureMessage());
            } catch (RuntimeException e) {
                log.warn("[审计] 补记请求事件失败：{} {}，原因={}",
                        request.getMethod(), request.getRequestURI(), e.getMessage());
            } finally {
                AuditContext.clear();
            }
        }
    }

    private AuthUser actor(HttpServletRequest request) {
        try {
            return authService.resolve(request).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** 小体积 JSON 请求体缓存；上传等二进制或大请求体直接透传，不落审计。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final Charset charset;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.charset = charset(request.getCharacterEncoding());
        }

        private static CachedBodyRequest wrap(HttpServletRequest request) {
            return new CachedBodyRequest(request, capture(request));
        }

        private static byte[] capture(HttpServletRequest request) {
            long length = request.getContentLengthLong();
            if (length <= 0 || length > MAX_BODY_BYTES || !isJson(request.getContentType())) {
                return null;
            }
            try {
                return request.getInputStream().readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }

        private static boolean isJson(String contentType) {
            if (contentType == null) {
                return false;
            }
            String normalized = contentType.toLowerCase(Locale.ROOT);
            return normalized.startsWith("application/json") || normalized.contains("+json");
        }

        private static Charset charset(String encoding) {
            try {
                return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            } catch (RuntimeException e) {
                return StandardCharsets.UTF_8;
            }
        }

        private String cachedBody() {
            return body == null ? null : new String(body, charset);
        }

        @Override
        public ServletInputStream getInputStream() {
            if (body == null) {
                return superInputStream();
            }
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }
            };
        }

        private ServletInputStream superInputStream() {
            try {
                return super.getInputStream();
            } catch (IOException e) {
                throw new IllegalStateException("读取请求体失败", e);
            }
        }

        @Override
        public BufferedReader getReader() {
            if (body == null) {
                try {
                    return super.getReader();
                } catch (IOException e) {
                    throw new IllegalStateException("读取请求体失败", e);
                }
            }
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
