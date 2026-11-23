package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class LlmUsageWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:usage-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final String ADMIN_PASSWORD = "Usage@Test123";

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
    }

    @LocalServerPort
    private int port;

    @Autowired
    private LlmUsageStore usageStore;

    @Test
    @DisplayName("用量看板：匿名 401、非授权角色 403、管理员可读且字段为 snake_case")
    void usageOverviewRequiresUsageRead() throws Exception {
        HttpClient anonymous = newClient();
        assertEquals(401, getRaw(anonymous, "/api/admin/usage/overview").statusCode());

        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        usageStore.insert(
                record(
                        LocalDateTime.now().minusMinutes(10),
                        "qwen2.5:3b",
                        "控制面",
                        "control",
                        "薪酬助手",
                        "7",
                        100,
                        20,
                        30,
                        200,
                        true,
                        null));
        usageStore.insert(
                record(
                        LocalDateTime.now().minusMinutes(5),
                        "deepseek-coder",
                        "编码面",
                        "implementer",
                        "开发",
                        "8",
                        50,
                        10,
                        0,
                        400,
                        false,
                        "IllegalStateException"));

        String all = get(admin, "/api/admin/usage/overview");
        assertTrue(all.contains("\"status\":\"OK\""), all);
        assertTrue(all.contains("\"granularity\":\"hour\""), all);
        assertTrue(all.contains("\"calls\":2"), all);
        assertTrue(all.contains("\"successes\":1"), all);
        assertTrue(all.contains("\"failures\":1"), all);
        assertTrue(all.contains("\"total_tokens\":180"), all);
        assertTrue(all.contains("\"avg_latency_ms\":300"), all);
        assertTrue(all.contains("\"active_users\":2"), all);
        assertTrue(all.contains("\"active_agents\":2"), all);
        assertTrue(all.contains("\"by_model\""), all);
        assertTrue(all.contains("\"by_plane\""), all);
        assertTrue(all.contains("\"by_agent\""), all);
        assertTrue(all.contains("\"by_user\""), all);
        assertTrue(all.contains("\"qwen2.5:3b\""), all);
        assertTrue(all.contains("\"deepseek-coder\""), all);
        assertTrue(all.contains("\"recent\":[{\"id\":2"), all);
        assertFalse(all.contains("totalTokens"), all);
        assertFalse(all.contains("byModel"), all);

        String weekly = get(admin, "/api/admin/usage/overview?range=7d");
        assertTrue(weekly.contains("\"granularity\":\"day\""), weekly);

        String filtered =
                get(
                        admin,
                        "/api/admin/usage/overview?range=24h&plane="
                                + URLEncoder.encode("编码面", StandardCharsets.UTF_8));
        assertTrue(filtered.contains("\"calls\":1"), filtered);
        assertTrue(filtered.contains("\"total_tokens\":60"), filtered);
        assertTrue(filtered.contains("\"by_plane\":[{\"key\":\"编码面\""), filtered);
        assertFalse(filtered.contains("qwen2.5:3b"), filtered);

        createViewer(admin, "viewer_usage", "viewer-usage-pass");
        HttpClient viewer = newClient();
        login(viewer, "viewer_usage", "viewer-usage-pass");
        assertEquals(403, getRaw(viewer, "/api/admin/usage/overview").statusCode());
        assertFalse(Permissions.builtinRolePermissions().get(Permissions.ROLE_VIEWER).contains(Permissions.USAGE_READ));
        assertFalse(Permissions.builtinRolePermissions().get(Permissions.ROLE_HR).contains(Permissions.USAGE_READ));
    }

    private void createViewer(HttpClient admin, String username, String password) throws Exception {
        String body =
                """
                {"username":"%s","display_name":"用量访客","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        HttpResponse<String> created = post(admin, "/api/admin/users", body);
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"role_code\":\"VIEWER\""), created.body());
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                post(
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
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static LlmUsageRecord record(
            LocalDateTime occurredAt,
            String model,
            String plane,
            String agentId,
            String agentName,
            String userId,
            int prompt,
            int completion,
            int cached,
            long latencyMs,
            boolean success,
            String errorType) {
        return new LlmUsageRecord(
                0L,
                occurredAt,
                userId,
                "42",
                agentId,
                agentName,
                "implementer",
                "dev.task",
                9L,
                model,
                "ollama",
                plane,
                prompt,
                completion,
                cached,
                prompt + completion,
                latencyMs,
                success,
                errorType,
                "reply-" + model);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-usage-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
