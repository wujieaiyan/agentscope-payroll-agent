package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.service.InternalAuth;
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
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 校验 runtime 侧知识库工具所依赖的签名内部接口：签名、权限、审计与真实落库。 */
@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KbSkillInternalTest {

    private static final String ADMIN_PASSWORD = "kb-internal-admin";
    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String VIEWER_PASSWORD = "kb-internal-viewer";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:kb-skill-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.kb.storage-dir", () -> OUTPUT_DIR.resolve("kb").toString());
        registry.add("payroll.kb.es-enabled", () -> "false");
        registry.add("payroll.kb.embedding-base-url", () -> "http://127.0.0.1:1");
        registry.add("payroll.kb.answer-enabled", () -> "false");
        registry.add("payroll.internal.token", () -> INTERNAL_TOKEN);
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("内部知识库工具：签名校验、读放行、无权限拒绝并审计、建库按部门名称落库")
    void enforcesInternalAuthAndKbPermissions() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        String departments = get(admin, "/api/admin/departments");
        long deptId = longField(departments, "id");
        Matcher nameMatcher =
                Pattern.compile("\"id\":" + deptId + ",\"name\":\"([^\"]+)\"").matcher(departments);
        assertTrue(nameMatcher.find(), departments);
        String departmentName = nameMatcher.group(1);

        assertEquals(
                401,
                unsigned("/internal/kb/check", payload(0L, "kb_list_libraries", "{}"))
                        .statusCode());

        String viewer =
                createUser(admin, "kb_internal_viewer", VIEWER_PASSWORD, "VIEWER");
        long viewerId = longField(viewer, "id");

        String readCheck =
                signedPost("/internal/kb/check", payload(viewerId, "kb_list_libraries", "{}"));
        assertTrue(readCheck.contains("\"allowed\":true"), readCheck);

        String writeCheck =
                signedPost(
                        "/internal/kb/check",
                        payload(viewerId, "kb_save_config", "{\"series_id\":1,\"retrieval_top_k\":8}"));
        assertTrue(writeCheck.contains("\"allowed\":false"), writeCheck);
        assertTrue(writeCheck.contains("管理知识库"), writeCheck);

        String audits = get(admin, "/api/audit?action=kb.skill.denied");
        assertTrue(audits.contains("kb.skill.denied"), audits);
        assertTrue(audits.contains("kb_save_config"), audits);

        long adminId = userId(admin, "admin");
        String created =
                signedPost(
                        "/internal/kb/execute",
                        payload(
                                adminId,
                                "kb_create_library",
                                """
                                {"name":"研发部制度库-%s","department_name":"%s",
                                 "retrieval_top_k":8}
                                """
                                        .formatted(
                                                UUID.randomUUID().toString().substring(0, 6),
                                                departmentName)));
        assertTrue(created.contains("\"status\":\"OK\""), created);
        assertTrue(created.contains("\"name\":\"研发部制度库-"), created);
        long seriesId = longField(created, "id");

        String config =
                signedPost(
                        "/internal/kb/execute",
                        payload(adminId, "kb_get_config", "{\"series_id\":" + seriesId + "}"));
        assertTrue(config.contains("\"status\":\"OK\""), config);
        assertTrue(config.contains("\"recallTopK\":8"), config);

        String executeRead =
                signedPost("/internal/kb/execute", payload(viewerId, "kb_list_libraries", "{}"));
        assertTrue(executeRead.contains("\"status\":\"OK\""), executeRead);
        assertFalse(executeRead.contains("\"status\":\"FORBIDDEN\""), executeRead);
    }

    private HttpResponse<String> unsigned(String path, String body) throws Exception {
        return postRaw(path, body, null, String.valueOf(Instant.now().getEpochSecond()));
    }

    private String signedPost(String path, String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(INTERNAL_TOKEN, "POST", path, timestamp, body);
        HttpResponse<String> response = postRaw(path, body, signature, timestamp);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRaw(String path, String body, String signature, String timestamp)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (signature != null) {
            builder.header("X-Payroll-Internal", signature);
        }
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String payload(long userId, String tool, String arguments) {
        return """
               {"user_id":%d,"tool":"%s","arguments":%s}
               """
                .formatted(userId, tool, arguments);
    }

    private long userId(HttpClient admin, String username) throws Exception {
        String body = get(admin, "/api/admin/users");
        Matcher matcher =
                Pattern.compile("\"id\":(\\d+),[^}]*\"username\":\"" + Pattern.quote(username) + "\"")
                        .matcher(body);
        if (!matcher.find()) {
            matcher = Pattern.compile("\"username\":\"" + Pattern.quote(username) + "\"").matcher(body);
            assertTrue(matcher.find(), body);
        }
        String window = body.substring(Math.max(0, matcher.start() - 200), matcher.end());
        Matcher idMatcher = Pattern.compile("\"id\":(\\d+)").matcher(window);
        assertTrue(idMatcher.find(), body);
        return Long.parseLong(idMatcher.group(1));
    }

    private long longField(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private String createUser(HttpClient admin, String username, String password, String role)
            throws Exception {
        return post(
                admin,
                "/api/admin/users",
                """
                {"username":"%s","display_name":"%s","password":"%s","role_code":"%s"}
                """
                        .formatted(username, username, password, role));
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + password
                                                        + "\"}",
                                                StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private String get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String post(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-kb-skill-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
