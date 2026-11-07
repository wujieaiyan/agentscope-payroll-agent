package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:audit-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final String ADMIN_PASSWORD = "Audit@Test123";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("登录成功/失败、写接口与退出均写入审计，且支持筛选和 CSV 导出")
    void auditsAuthenticationAndWrites() throws Exception {
        HttpResponse<String> denied =
                post("/api/auth/login", "{\"username\":\"admin\",\"password\":\"wrong\"}", null);
        assertEquals(401, denied.statusCode());

        String cookie = login();
        HttpResponse<String> created =
                post("/api/chat/sessions", "{\"title\":\"审计验证\"}", cookie);
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"id\""));

        String all = get("/api/audit?size=100", cookie);
        assertTrue(all.contains("\"action\":\"auth.login\""), all);
        assertTrue(all.contains("post /api/chat/sessions"), all);
        assertTrue(all.contains("\"actor_name\":\"admin\""), all);

        String failures = get("/api/audit?result=FAILURE&keyword=auth.login", cookie);
        assertTrue(failures.contains("用户名或密码错误"), failures);

        post("/api/auth/logout", "", cookie);
        String secondCookie = login();
        String logout = get("/api/audit?action=auth.logout", secondCookie);
        assertTrue(logout.contains("\"action\":\"auth.logout\""), logout);

        HttpResponse<byte[]> csv = getBytes("/api/audit/export", secondCookie);
        assertEquals(200, csv.statusCode());
        String text = new String(csv.body(), StandardCharsets.UTF_8);
        assertTrue(text.contains("时间,操作人,动作"), text);
        assertTrue(text.contains("auth.login"), text);
        assertTrue(text.contains("用户名或密码错误"), text);
    }

    @Test
    @DisplayName("未登录不能读取审计日志，访客角色也没有 audit:read")
    void auditEndpointRequiresLogin() throws Exception {
        assertEquals(401, getStatus("/api/audit"));
        assertTrue(
                io.agentscope.examples.payroll.support.Permissions.builtinRolePermissions()
                        .get(io.agentscope.examples.payroll.support.Permissions.ROLE_VIEWER)
                        .stream()
                        .noneMatch(
                                io.agentscope.examples.payroll.support.Permissions.AUDIT_READ
                                        ::equals));
    }

    private String login() throws Exception {
        HttpResponse<String> response =
                post(
                        "/api/auth/login",
                        "{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                        null);
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.split(";", 2)[0];
    }

    private String get(String path, String cookie) throws Exception {
        HttpResponse<String> response = sendGet(path, cookie, HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<byte[]> getBytes(String path, String cookie) throws Exception {
        return sendGet(path, cookie, HttpResponse.BodyHandlers.ofByteArray());
    }

    private int getStatus(String path) throws Exception {
        return sendGet(path, null, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private <T> HttpResponse<T> sendGet(
            String path, String cookie, HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), handler);
    }

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null || body.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-audit-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
