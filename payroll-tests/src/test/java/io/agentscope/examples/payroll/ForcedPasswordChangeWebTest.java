package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** O6：新建与重置密码的用户必须首改密，存量与关闭开关时不受影响。 */
@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class ForcedPasswordChangeWebTest {

    private static final String ADMIN_PASSWORD = "forced-admin-pass";
    private static final String INITIAL_PASSWORD = "forced-initial-pass";
    private static final String NEW_PASSWORD = "forced-updated-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:forced-password-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.auth.force-password-change", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("新建用户登录后只能改密，改密完成恢复正常访问")
    void newUserMustChangePasswordThenRecovers() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long userId = createUser(admin, "forced_user", INITIAL_PASSWORD);

        HttpClient user = newClient();
        HttpResponse<String> login = loginRaw(user, "forced_user", INITIAL_PASSWORD);
        assertEquals(200, login.statusCode(), login.body());
        assertTrue(login.body().contains("\"must_change_password\":true"), login.body());

        HttpResponse<String> blocked = getRaw(user, "/api/admin/roles");
        assertEquals(403, blocked.statusCode(), blocked.body());
        assertTrue(blocked.body().contains("请先修改初始密码"), blocked.body());

        String me = get(user, "/api/auth/me");
        assertTrue(me.contains("\"must_change_password\":true"), me);

        HttpResponse<String> wrongOld =
                postRaw(
                        user,
                        "/api/auth/password",
                        "{\"old_password\":\"bad-old-pass\",\"new_password\":\""
                                + NEW_PASSWORD
                                + "\"}");
        assertEquals(400, wrongOld.statusCode(), wrongOld.body());
        assertEquals(403, getRaw(user, "/api/admin/roles").statusCode());

        HttpResponse<String> changed =
                postRaw(
                        user,
                        "/api/auth/password",
                        "{\"old_password\":\""
                                + INITIAL_PASSWORD
                                + "\",\"new_password\":\""
                                + NEW_PASSWORD
                                + "\"}");
        assertEquals(200, changed.statusCode(), changed.body());

        assertEquals(200, getRaw(user, "/api/admin/roles").statusCode());
        assertTrue(
                get(user, "/api/auth/me").contains("\"must_change_password\":false"),
                get(user, "/api/auth/me"));
        assertTrue(get(admin, "/api/admin/users").contains("\"id\":" + userId));
    }

    @Test
    @DisplayName("管理员重置密码后用户再次被要求改密")
    void resetPasswordRequiresChangeAgain() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long userId = createUser(admin, "forced_reset", INITIAL_PASSWORD);

        HttpClient user = newClient();
        login(user, "forced_reset", INITIAL_PASSWORD);
        post(
                user,
                "/api/auth/password",
                "{\"old_password\":\""
                        + INITIAL_PASSWORD
                        + "\",\"new_password\":\""
                        + NEW_PASSWORD
                        + "\"}");
        assertEquals(200, getRaw(user, "/api/admin/roles").statusCode());

        String resetPassword = "forced-reset-pass";
        assertEquals(
                200,
                postRaw(
                                admin,
                                "/api/admin/users/" + userId + "/password",
                                "{\"password\":\"" + resetPassword + "\"}")
                        .statusCode());

        HttpClient afterReset = newClient();
        HttpResponse<String> login = loginRaw(afterReset, "forced_reset", resetPassword);
        assertTrue(login.body().contains("\"must_change_password\":true"), login.body());
        assertEquals(403, getRaw(afterReset, "/api/admin/roles").statusCode());
    }

    @Test
    @DisplayName("引导管理员属存量账号，不被强制改密")
    void bootstrapAdminIsNotForced() throws Exception {
        HttpClient admin = newClient();
        HttpResponse<String> login = loginRaw(admin, "admin", ADMIN_PASSWORD);

        assertEquals(200, login.statusCode(), login.body());
        assertTrue(login.body().contains("\"must_change_password\":false"), login.body());
        assertEquals(200, getRaw(admin, "/api/admin/roles").statusCode());
        assertEquals(200, getRaw(admin, "/api/auth/me").statusCode());
    }

    @Test
    @DisplayName("未登录仍返回 401，不被改密拦截掩盖")
    void anonymousKeepsUnauthorized() throws Exception {
        HttpClient anonymous = newClient();

        HttpResponse<String> response = getRaw(anonymous, "/api/admin/roles");

        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("未登录"), response.body());
        assertFalse(response.body().contains("请先修改初始密码"), response.body());
    }

    private long createUser(HttpClient admin, String username, String password) throws Exception {
        String body =
                """
                {"username":"%s","display_name":"首改密用户","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        String created = post(admin, "/api/admin/users", body);
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(created);
        assertTrue(matcher.find(), created);
        return Long.parseLong(matcher.group(1));
    }

    private HttpResponse<String> loginRaw(HttpClient client, String username, String password)
            throws Exception {
        return postRaw(
                client,
                "/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response = loginRaw(client, username, password);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"username\":\"" + username + "\""), response.body());
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> getRaw(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = getRaw(client, path);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String post(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = postRaw(client, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-forced-password-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
