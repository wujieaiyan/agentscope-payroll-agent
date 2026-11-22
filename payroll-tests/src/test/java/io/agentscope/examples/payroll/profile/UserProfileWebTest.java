package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.PayrollWebApplication;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserProfileWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:profile-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final String ADMIN_PASSWORD = "Profile@Admin1";
    private static final String HR_PASSWORD = "Profile@Hr1234";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        // 新建用户默认强制首改密，本类聚焦其他能力，关闭该开关；专项覆盖见 ForcedPasswordChangeWebTest。
        registry.add("payroll.auth.force-password-change", () -> "false");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.memory.profile.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("登录态返回 profile_enabled，匿名访问偏好接口返回 401")
    void authFlagAndAnonymous() throws Exception {
        assertEquals(401, getAnonymous("/api/profile").statusCode());

        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        String me = get(admin, "/api/auth/me");
        assertTrue(me.contains("\"profile_enabled\":true"), me);
    }

    @Test
    @DisplayName("HR 可读写自己的偏好，响应字段为 snake_case")
    void hrReadsAndWritesOwnProfile() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "profile-hr", HR_PASSWORD);

        HttpClient hr = newClient();
        login(hr, "profile-hr", HR_PASSWORD);

        String created =
                post(hr, "/api/profile/items", "{\"category\":\"ROLE\",\"key\":\"region\",\"value\":\"上海\"}");
        assertTrue(created.contains("\"item_value\":\"上海\""), created);
        assertTrue(created.contains("\"label\":\"职责范围\""), created);
        assertFalse(created.contains("\"itemValue\""), created);

        String list = get(hr, "/api/profile");
        assertTrue(list.contains("\"items\""), list);
        assertTrue(list.contains("\"options\""), list);
        assertTrue(list.contains("\"item_id\""), list);
        assertTrue(list.contains("上海"), list);
    }

    @Test
    @DisplayName("词表外的值返回 400，跨用户删除返回 404，HR 不能合规删除")
    void rejectsInvalidAndCrossUserRequests() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "profile-hr2", HR_PASSWORD);

        HttpResponse<String> invalid =
                postRaw(
                        admin,
                        "/api/profile/items",
                        "{\"category\":\"ROLE\",\"key\":\"region\",\"value\":\"火星\"}");
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertTrue(invalid.body().contains("不在允许范围"), invalid.body());

        String adminItem =
                post(admin, "/api/profile/items", "{\"category\":\"TOPIC\",\"key\":\"focus\",\"value\":\"个税\"}");
        long adminItemId = firstLong(adminItem, "item_id");

        HttpClient hr = newClient();
        login(hr, "profile-hr2", HR_PASSWORD);
        assertEquals(404, deleteRaw(hr, "/api/profile/items/" + adminItemId).statusCode());
        assertEquals(403, deleteRaw(hr, "/api/admin/profile/1").statusCode());
    }

    @Test
    @DisplayName("停用后条目仍可读取但状态为 DISABLED")
    void togglesStatusThroughApi() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "profile-hr3", HR_PASSWORD);

        HttpClient hr = newClient();
        login(hr, "profile-hr3", HR_PASSWORD);
        String created =
                post(hr, "/api/profile/items", "{\"category\":\"SCOPE\",\"key\":\"default_period\",\"value\":\"本月\"}");
        long itemId = firstLong(created, "item_id");

        String updated = put(hr, "/api/profile/items/" + itemId, "{\"status\":\"DISABLED\"}");
        assertTrue(updated.contains("\"status\":\"DISABLED\""), updated);

        String list = get(hr, "/api/profile");
        assertTrue(list.contains("\"status\":\"DISABLED\""), list);
    }

    @Test
    @DisplayName("管理员可合规删除指定用户偏好")
    void adminCanPurge() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "profile-hr4", HR_PASSWORD);
        long hrId = userId(admin, "profile-hr4");

        String purged = delete(admin, "/api/admin/profile/" + hrId);
        assertTrue(purged.contains("\"deleted\""), purged);
        assertTrue(purged.contains("\"userId\":" + hrId), purged);
    }

    private void createUser(HttpClient admin, String username, String password) throws Exception {
        String body =
                """
                {"username":"%s","display_name":"%s","password":"%s","role_code":"HR"}
                """
                        .formatted(username, username, password);
        get(admin, "/api/admin/roles");
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
    }

    private long userId(HttpClient admin, String username) throws Exception {
        String body = get(admin, "/api/admin/users");
        int usernameIndex = body.indexOf("\"username\":\"" + username + "\"");
        assertTrue(usernameIndex >= 0, body);
        int objectStart = body.lastIndexOf('{', usernameIndex);
        Matcher matcher =
                Pattern.compile("\"id\":(\\d+)")
                        .matcher(body.substring(Math.max(0, objectStart), usernameIndex));
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private long firstLong(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        client,
                        "/api/auth/login",
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private String get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = getRaw(client, path);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> getRaw(HttpClient client, String path) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> getAnonymous(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String post(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = postRaw(client, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String put(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = send(client, path, "PUT", body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String delete(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = deleteRaw(client, path);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, path, "POST", body);
    }

    private HttpResponse<String> deleteRaw(HttpClient client, String path) throws Exception {
        return send(client, path, "DELETE", null);
    }

    private HttpResponse<String> send(HttpClient client, String path, String method, String body)
            throws Exception {
        HttpRequest.BodyPublisher publisher =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .method(method, publisher);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-profile-web-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
