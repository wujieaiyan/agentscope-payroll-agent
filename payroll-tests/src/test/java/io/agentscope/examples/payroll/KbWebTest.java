package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
class KbWebTest {

    private static final String ADMIN_PASSWORD = "kb-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:kb-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

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
    @DisplayName("知识库创建后按库级角色控制访问，配置保存生成新版本")
    void enforcesSeriesLevelRolesAndConfigVersions() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long deptId = firstDepartmentId(admin);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        createUser(admin, "kb_viewer_" + suffix, "kb-viewer-pass", "VIEWER");
        createUser(admin, "kb_hr_" + suffix, "kb-hr-pass", "HR");

        HttpClient viewer = newClient();
        login(viewer, "kb_viewer_" + suffix, "kb-viewer-pass");
        HttpClient hr = newClient();
        login(hr, "kb_hr_" + suffix, "kb-hr-pass");

        String viewerCreate =
                """
                {"name":"越权知识库","deptId":%d,"indexStrategy":"HYBRID","draft":false}
                """
                        .formatted(deptId);
        assertEquals(403, postRaw(viewer, "/api/kb/series", viewerCreate).statusCode());

        String created =
                post(
                        hr,
                        "/api/kb/series",
                        """
                        {"name":"薪酬制度库-%s","deptId":%d,"description":"测试用制度库",
                         "indexStrategy":"HYBRID","draft":false}
                        """
                                .formatted(suffix, deptId));
        assertTrue(created.contains("\"role\":\"OWNER\""), created);
        long seriesId = longField(created, "id");

        assertEquals(403, getRaw(viewer, "/api/kb/series/" + seriesId).statusCode());
        assertFalse(get(viewer, "/api/kb/series").contains("\"id\":" + seriesId));

        String member =
                post(
                        admin,
                        "/api/kb/series/" + seriesId + "/members",
                        """
                        {"subjectType":"USER","subjectIds":[%d],"role":"READER"}
                        """
                                .formatted(userId(admin, "kb_viewer_" + suffix)));
        assertTrue(member.contains("\"role\":\"READER\""), member);

        assertEquals(200, getRaw(viewer, "/api/kb/series/" + seriesId).statusCode());
        assertEquals(
                403,
                patchRaw(viewer, "/api/kb/series/" + seriesId, """
                        {"name":"改名","rowVersion":1}
                        """)
                        .statusCode());
        assertEquals(403, deleteRaw(viewer, "/api/kb/series/" + seriesId).statusCode());

        String config = get(admin, "/api/kb/series/" + seriesId + "/config");
        assertTrue(config.contains("\"versionNo\":1"), config);
        String payload =
                """
                {"indexStrategy":"VECTOR",
                 "parseConfig":%s,
                 "chunkConfig":%s,
                 "retrievalConfig":%s,
                 "changeNote":"调整切片",
                 "rebuildMode":"NONE"}
                """
                        .formatted(
                                extractObject(config, "parseConfig"),
                                extractObject(config, "chunkConfig"),
                                extractObject(config, "retrievalConfig"));
        String saved = post(admin, "/api/kb/series/" + seriesId + "/config-versions", payload);
        assertTrue(saved.contains("\"versionNo\":2"), saved);
        assertTrue(saved.contains("\"changeNote\":\"调整切片\""), saved);
        assertTrue(
                get(admin, "/api/kb/series/" + seriesId + "/config-versions")
                        .contains("\"versionNo\":2"));
    }

    @Test
    @DisplayName("上传 Markdown 后异步入库，切片与检索可用本地回退")
    void ingestsMarkdownAndSearchesWithLocalFallback() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long deptId = firstDepartmentId(admin);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String created =
                post(
                        admin,
                        "/api/kb/series",
                        """
                        {"name":"年假制度库-%s","deptId":%d,"description":"年假与休假规则",
                         "indexStrategy":"HYBRID","draft":false}
                        """
                                .formatted(suffix, deptId));
        long seriesId = longField(created, "id");

        String markdown =
                """
                # 年假制度

                员工入职满一年后享有带薪年假。

                ## 天数

                工作满一年不满十年的员工，每年年假为 5 天；满十年不满二十年的为 10 天；
                满二十年的为 15 天。

                ## 申请流程

                年假需提前三个工作日在系统内提交申请，由直属主管审批。
                """;
        String upload =
                uploadMarkdown(admin, seriesId, "年假制度.md", markdown);
        assertTrue(upload.contains("\"uploaded\":1"), upload);

        String documents = awaitDocumentsReady(admin, seriesId);
        assertTrue(documents.contains("\"status\":\"READY\""), documents);
        assertTrue(documents.contains("年假制度.md"), documents);
        long documentId = firstDocumentId(documents);

        String chunks =
                get(admin, "/api/kb/series/" + seriesId + "/documents/" + documentId + "/chunks?page=1&size=20");
        assertTrue(chunks.contains("年假"), chunks);
        assertTrue(chunks.contains("\"total\":"), chunks);

        String retrieval =
                post(
                        admin,
                        "/api/kb/series/" + seriesId + "/retrieval/test",
                        """
                        {"query":"工作满一年有多少天年假","topK":5,"threshold":0.0,"withAnswer":false}
                        """);
        assertTrue(retrieval.contains("\"hits\":["), retrieval);
        assertTrue(retrieval.contains("年假制度.md"), retrieval);
        assertTrue(retrieval.contains("\"engine\":\"LOCAL\""), retrieval);

        String summary = get(admin, "/api/kb/series/" + seriesId + "/documents/status-summary");
        assertTrue(summary.contains("\"ready\":1"), summary);
    }

    private String awaitDocumentsReady(HttpClient client, long seriesId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = get(client, "/api/kb/series/" + seriesId + "/documents");
            if (last.contains("\"status\":\"READY\"") || last.contains("\"status\":\"FAILED\"")) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

    private long firstDocumentId(String body) {
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private long firstDepartmentId(HttpClient admin) throws Exception {
        String body = get(admin, "/api/admin/departments");
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private long userId(HttpClient admin, String username) throws Exception {
        String body = get(admin, "/api/admin/users");
        Matcher matcher =
                Pattern.compile("\"id\":(\\d+),[^}]*\"username\":\"" + Pattern.quote(username) + "\"")
                        .matcher(body);
        if (!matcher.find()) {
            matcher = Pattern.compile("\"username\":\"" + Pattern.quote(username) + "\"")
                    .matcher(body);
            assertTrue(matcher.find(), body);
        }
        String window = body.substring(Math.max(0, matcher.start() - 200), matcher.end());
        Matcher idMatcher = Pattern.compile("\"id\":(\\d+)").matcher(window);
        assertTrue(idMatcher.find(), body);
        return Long.parseLong(idMatcher.group(1));
    }

    private String extractObject(String json, String field) {
        int key = json.indexOf("\"" + field + "\"");
        assertTrue(key >= 0, json);
        int start = json.indexOf('{', key);
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("无法解析字段 " + field);
    }

    private long longField(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private String uploadMarkdown(
            HttpClient client, long seriesId, String filename, String content) throws Exception {
        String boundary = "----kb-test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(
                ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: text/markdown; charset=UTF-8\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"dedupe_action\"\r\n\r\nSKIP\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/api/kb/series/" + seriesId + "/documents"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private void createUser(HttpClient admin, String username, String password, String role)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"%s","password":"%s","role_code":"%s"}
                """
                        .formatted(username, username, password, role);
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
        return send(client, "POST", path, body);
    }

    private HttpResponse<String> patchRaw(HttpClient client, String path, String body)
            throws Exception {
        return send(client, "PATCH", path, body);
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
            return Files.createTempDirectory("payroll-kb-web-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
