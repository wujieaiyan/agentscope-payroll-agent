package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class FlowAdminWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:flow-admin-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final String ADMIN_PASSWORD = "Flow@Admin123";
    private static final String HR_PASSWORD = "Flow@Hr12345";

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
    @DisplayName("管理员可读取流程列表与画布元数据，字段为 camelCase")
    void adminReadsFlows() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        String flows = get(admin, "/api/admin/flows");
        assertTrue(flows.contains("\"activeFlowId\""), flows);
        assertTrue(flows.contains("\"writable\""), flows);
        assertTrue(flows.contains("\"location\""), flows);
        assertTrue(flows.contains("\"flows\""), flows);
        assertTrue(flows.contains("payroll-default"), flows);

        String meta = get(admin, "/api/admin/flows/meta");
        assertTrue(meta.contains("\"writable\""), meta);
        assertTrue(meta.contains("\"agents\""), meta);
        assertTrue(meta.contains("\"status\""), meta);
    }

    @Test
    @DisplayName("未登录返回 401，HR 没有 flow:manage 返回 403")
    void requiresFlowPermission() throws Exception {
        assertEquals(401, getAnonymous("/api/admin/flows").statusCode());

        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "flow-hr", HR_PASSWORD, "HR");

        HttpClient hr = newClient();
        login(hr, "flow-hr", HR_PASSWORD);
        assertEquals(403, getRaw(hr, "/api/admin/flows").statusCode());
        assertEquals(403, getRaw(hr, "/api/admin/flows/meta").statusCode());

        String body =
                """
                {"name":"越权流程","nodes":[
                  {"id":"start","type":"START","name":"开始"},
                  {"id":"a","type":"AGENT","name":"计算","agentId":"payroll-calculator"},
                  {"id":"end","type":"END","name":"结束"}],
                 "edges":[{"id":"e1","source":"start","target":"a"},
                          {"id":"e2","source":"a","target":"end"}]}
                """;
        assertEquals(403, postRaw(hr, "/api/admin/flows", body).statusCode());
        assertEquals(403, putRaw(hr, "/api/admin/flows/any/active", "{}").statusCode());
    }

    @Test
    @DisplayName("非法流程返回 400 并给出结构错误")
    void rejectsInvalidFlow() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        HttpResponse<String> blankName =
                postRaw(admin, "/api/admin/flows", "{\"name\":\"  \"}");
        assertEquals(400, blankName.statusCode(), blankName.body());
        assertTrue(blankName.body().contains("流程名称必填"), blankName.body());

        String twoStarts =
                """
                {"name":"两个开始","nodes":[
                  {"id":"start","type":"START","name":"开始"},
                  {"id":"start-2","type":"START","name":"开始2"},
                  {"id":"a","type":"AGENT","name":"计算","agentId":"payroll-calculator"},
                  {"id":"end","type":"END","name":"结束"}],
                 "edges":[{"id":"e1","source":"start","target":"a"},
                          {"id":"e2","source":"start-2","target":"a"},
                          {"id":"e3","source":"a","target":"end"}]}
                """;
        HttpResponse<String> starts = postRaw(admin, "/api/admin/flows", twoStarts);
        assertEquals(400, starts.statusCode(), starts.body());
        assertTrue(starts.body().contains("恰好包含一个开始节点"), starts.body());

        HttpResponse<String> missingDelete =
                deleteRaw(admin, "/api/admin/flows/no-such-flow");
        assertEquals(400, missingDelete.statusCode(), missingDelete.body());
        assertTrue(missingDelete.body().contains("流程不存在"), missingDelete.body());
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
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        return client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-flow-admin-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
