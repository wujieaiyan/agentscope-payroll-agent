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
class PayrollAdminWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("员工接口、预览/出账/发送与 Excel 下载可用，静态页面由前端独立托管")
    void servesAdminWorkflow() throws Exception {
        assertEquals(404, getStatus("/"));

        String employees = get("/api/employees?include_inactive=true");
        assertTrue(employees.contains("EMP001"));
        assertTrue(employees.contains("\"active\":true"));

        String preview =
                post(
                        "/api/runs/preview",
                        "{\"period\":\"2026-10\",\"scope\":\"全部\",\"adjustments\":[]}");
        assertTrue(preview.contains("\"DRAFT\""));
        long runId = extractRunId(preview);

        String submitted = post("/api/runs/" + runId + "/submit-approval", "{\"comment\":\"月度核算\"}");
        assertTrue(submitted.contains("\"PENDING_APPROVAL\""), submitted);
        assertTrue(submitted.contains("默认审批链"), submitted);

        String approved = post("/api/runs/" + runId + "/approve", "{\"comment\":\"同意\"}");
        assertTrue(approved.contains("\"APPROVED\""), approved);
        assertTrue(approved.contains("\"APPROVE\""), approved);

        String finalized = post("/api/runs/" + runId + "/finalize", "");
        assertTrue(finalized.contains("\"FINALIZED\""));

        String report = post("/api/runs/" + runId + "/send", "{\"confirm\":true}");
        assertTrue(report.contains("\"dry_run\":true"));
        assertTrue(report.contains("\"failure_count\":1"));

        HttpResponse<byte[]> excel =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(baseUrl() + "/api/runs/" + runId + "/excel"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, excel.statusCode());
        assertTrue(excel.body().length > 0);

        String runs = get("/api/runs");
        assertTrue(runs.contains("\"SENT\""));
    }

    @Test
    @DisplayName("请求体为空或格式错误返回 400 与统一错误结构")
    void rejectsInvalidRequests() throws Exception {
        HttpResponse<String> chat =
                postRaw(
                        "/api/chat",
                        "{\"message\":\"   \"}",
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, chat.statusCode());
        assertTrue(chat.body().contains("\"status\":\"ERROR\""));
        assertTrue(chat.body().contains("请输入要咨询的问题"));

        assertEquals(404, getStatus("/api/bogus"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private int getStatus(String path) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private String post(String path, String body) throws Exception {
        HttpResponse<String> response =
                postRaw(path, body, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private <T> HttpResponse<T> postRaw(
            String path, String body, HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null || body.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8))
                        .build();
        return client.send(request, handler);
    }

    private static long extractRunId(String json) {
        Matcher matcher = Pattern.compile("\"run_id\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), json);
        return Long.parseLong(matcher.group(1));
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-web-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
