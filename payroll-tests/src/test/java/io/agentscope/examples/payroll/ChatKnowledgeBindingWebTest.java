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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatKnowledgeBindingWebTest {

    private static final String ADMIN_PASSWORD = "kb-bind-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-kb-binding-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

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
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("会话可绑定多个知识库并持久化，超过上限与不存在库被拒绝")
    void bindsAndPersistsKnowledgeSeries() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long deptId = firstDepartmentId(admin);
        long first = createSeries(admin, deptId, "绑定库甲");
        long second = createSeries(admin, deptId, "绑定库乙");
        long third = createSeries(admin, deptId, "绑定库丙");
        long fourth = createSeries(admin, deptId, "绑定库丁");
        long sessionId = createSession(admin, "绑定验证");

        String empty = get(admin, knowledgePath(sessionId));
        assertTrue(empty.contains("\"series\":[]"), empty);
        assertTrue(empty.contains("\"max_series\":3"), empty);

        String bound =
                put(
                        admin,
                        knowledgePath(sessionId),
                        "{\"series_ids\":[" + first + "," + second + "]}");
        assertTrue(bound.contains("\"name\":\"绑定库甲-"), bound);
        assertTrue(bound.contains("\"name\":\"绑定库乙-"), bound);
        assertTrue(bound.contains("\"role\":\"OWNER\""), bound);
        assertTrue(bound.contains("\"searchable\":true"), bound);

        String refreshed = get(admin, knowledgePath(sessionId));
        assertTrue(refreshed.contains("\"id\":" + first), refreshed);
        assertTrue(refreshed.contains("\"id\":" + second), refreshed);

        assertEquals(
                400,
                putRaw(
                                admin,
                                knowledgePath(sessionId),
                                "{\"series_ids\":["
                                        + first
                                        + ","
                                        + second
                                        + ","
                                        + third
                                        + ","
                                        + fourth
                                        + "]}")
                        .statusCode());
        assertEquals(
                400,
                putRaw(admin, knowledgePath(sessionId), "{\"series_ids\":[999999]}").statusCode());

        String unbound = put(admin, knowledgePath(sessionId), "{\"series_ids\":[]}");
        assertTrue(unbound.contains("\"series\":[]"), unbound);

        String audit =
                get(admin, "/api/audit?action=chat.kb.bind&resource_id=" + sessionId);
        assertTrue(audit.contains("chat.kb.bind"), audit);
        assertTrue(audit.contains(Long.toString(sessionId)), audit);
    }

    @Test
    @DisplayName("绑定要求库级 READER：越权库 403 并写审计，失去成员后查询标记 denied")
    void enforcesSeriesReaderForBinding() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long deptId = firstDepartmentId(admin);
        long restricted = createSeries(admin, deptId, "受限制度库");
        long shared = createSeries(admin, deptId, "共享制度库");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String username = "kb_binder_" + suffix;
        createUser(admin, username, "kb-binder-pass");
        HttpClient viewer = newClient();
        login(viewer, username, "kb-binder-pass");
        long viewerId = userId(admin, username);
        long sessionId = createSession(viewer, "访客绑定");

        assertEquals(
                403,
                putRaw(
                                viewer,
                                knowledgePath(sessionId),
                                "{\"series_ids\":[" + restricted + "]}")
                        .statusCode());
        String deniedAudit =
                get(admin, "/api/audit?action=chat.kb.bind.denied&resource_id=" + sessionId);
        assertTrue(deniedAudit.contains("chat.kb.bind.denied"), deniedAudit);
        assertTrue(deniedAudit.contains("series_id\\\":" + restricted), deniedAudit);

        String granted =
                post(
                        admin,
                        "/api/kb/series/" + shared + "/members",
                        """
                        {"subjectType":"USER","subjectIds":[%d],"role":"READER"}
                        """
                                .formatted(viewerId));
        long memberId = memberIdOf(granted, viewerId);

        String bound =
                put(viewer, knowledgePath(sessionId), "{\"series_ids\":[" + shared + "]}");
        assertTrue(bound.contains("\"role\":\"READER\""), bound);
        assertTrue(bound.contains("\"name\":\"共享制度库-"), bound);

        HttpResponse<String> removed =
                deleteRaw(admin, "/api/kb/series/" + shared + "/members/" + memberId);
        assertEquals(200, removed.statusCode(), "grant=" + granted + " remove=" + removed.body());
        String afterRevoke = get(viewer, knowledgePath(sessionId));
        assertTrue(afterRevoke.contains("\"series\":[]"), afterRevoke);
        assertTrue(afterRevoke.contains("\"denied\":[" + shared + "]"), afterRevoke);
        assertFalse(afterRevoke.contains("共享制度库"), afterRevoke);
    }

    private String knowledgePath(long sessionId) {
        return "/api/chat/sessions/" + sessionId + "/knowledge";
    }

    private long createSeries(HttpClient admin, long deptId, String name) throws Exception {
        String created =
                post(
                        admin,
                        "/api/kb/series",
                        """
                        {"name":"%s-%s","deptId":%d,"indexStrategy":"HYBRID","draft":false}
                        """
                                .formatted(name, UUID.randomUUID().toString().substring(0, 6), deptId));
        return firstLong(created);
    }

    private void createUser(HttpClient admin, String username, String password) throws Exception {
        String body =
                """
                {"username":"%s","display_name":"绑定访客","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
    }

    private long firstDepartmentId(HttpClient admin) throws Exception {
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(get(admin, "/api/admin/departments"));
        assertTrue(matcher.find(), "缺少部门");
        return Long.parseLong(matcher.group(1));
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

    private static long memberIdOf(String body, long subjectId) {
        int subjectIndex = body.indexOf("\"subjectId\":" + subjectId);
        assertTrue(subjectIndex >= 0, body);
        int objectStart = body.lastIndexOf('{', subjectIndex);
        Matcher matcher =
                Pattern.compile("\"id\":(\\d+)")
                        .matcher(body.substring(Math.max(0, objectStart), subjectIndex));
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private static long firstLong(String body) {
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response = post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        return firstLong(response);
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        client,
                        "/api/auth/login",
                        "{\"username\":\""
                                + username
                                + "\",\"password\":\""
                                + password
                                + "\"}");
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

    private String put(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = putRaw(client, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> putRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, "PUT", path, body);
    }

    private String post(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = postRaw(client, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, "POST", path, body);
    }

    private HttpResponse<String> deleteRaw(HttpClient client, String path) throws Exception {
        return send(client, "DELETE", path, null);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8));
        return client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-kb-binding-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
