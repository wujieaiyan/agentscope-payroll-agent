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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentAdminWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:agent-admin-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final String ADMIN_PASSWORD = "Agent@Admin123";
    private static final String HR_PASSWORD = "Agent@Hr12345";

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

    @Test
    @DisplayName("管理员可读取智能体列表、存储信息与可选技能，字段为 camelCase")
    void adminReadsAgents() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        String agents = get(admin, "/api/admin/agents");
        assertTrue(agents.contains("\"payroll-calculator\""), agents);
        assertTrue(agents.contains("\"skillIds\""), agents);
        assertFalse(agents.contains("\"skill_ids\""), agents);

        String meta = get(admin, "/api/admin/agents/meta");
        assertTrue(meta.contains("\"writable\""), meta);
        assertTrue(meta.contains("\"location\""), meta);
        assertTrue(meta.contains("\"tags\""), meta);

        String skills = get(admin, "/api/admin/agents/skills");
        assertTrue(skills.contains("\"payroll-calculator\""), skills);
        assertTrue(skills.contains("\"source\""), skills);
    }

    @Test
    @DisplayName("未登录返回 401，HR 没有 agent:manage 返回 403")
    void requiresAgentPermission() throws Exception {
        assertEquals(401, getAnonymous("/api/admin/agents").statusCode());

        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "agent-hr", HR_PASSWORD, "HR");

        HttpClient hr = newClient();
        login(hr, "agent-hr", HR_PASSWORD);
        assertEquals(403, getRaw(hr, "/api/admin/agents").statusCode());
        assertEquals(
                403,
                postRaw(hr, "/api/admin/agents", "{\"name\":\"越权创建\"}").statusCode());
    }

    @Test
    @DisplayName("校验失败返回 400 并写入审计；不存在智能体的更新/删除被拒绝")
    void rejectsInvalidRequests() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        HttpResponse<String> blankName =
                postRaw(admin, "/api/admin/agents", "{\"name\":\"  \"}");
        assertEquals(400, blankName.statusCode(), blankName.body());
        assertTrue(blankName.body().contains("名称必填"), blankName.body());

        HttpResponse<String> unknownSkill =
                postRaw(
                        admin,
                        "/api/admin/agents",
                        "{\"name\":\"个税顾问\",\"skillIds\":[\"ghost-skill\"]}");
        assertEquals(400, unknownSkill.statusCode(), unknownSkill.body());
        assertTrue(unknownSkill.body().contains("未知技能"), unknownSkill.body());

        HttpResponse<String> badStatus =
                postRaw(
                        admin,
                        "/api/admin/agents",
                        "{\"name\":\"个税顾问\",\"status\":\"UNKNOWN\"}");
        assertEquals(400, badStatus.statusCode(), badStatus.body());
        assertTrue(badStatus.body().contains("状态取值非法"), badStatus.body());

        HttpResponse<String> missingUpdate =
                putRaw(admin, "/api/admin/agents/no-such-agent", "{\"name\":\"不存在\"}");
        assertEquals(400, missingUpdate.statusCode(), missingUpdate.body());
        assertTrue(missingUpdate.body().contains("智能体不存在"), missingUpdate.body());

        HttpResponse<String> missingDelete =
                deleteRaw(admin, "/api/admin/agents/no-such-agent");
        assertEquals(400, missingDelete.statusCode(), missingDelete.body());

        String audit = get(admin, "/api/audit?size=50&keyword=/api/admin/agents");
        assertTrue(audit.contains("post /api/admin/agents"), audit);
    }

    private void createUser(HttpClient admin, String username, String password, String role)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"%s","password":"%s","role_code":"%s"}
                """
                        .formatted(username, username, password, role);
        get(admin, "/api/admin/roles");
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
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

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, path, "POST", body);
    }

    private HttpResponse<String> putRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, path, "PUT", body);
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
            return Files.createTempDirectory("payroll-agent-admin-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
