package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.web.PayrollWebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollWebServerTest {

    private PayrollWebServer server;
    private String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("管理台首页、员工接口、预览/出账/发送与 Excel 下载可用")
    void servesAdminWorkflow() throws Exception {
        PayrollDatabase database =
                PayrollDatabase.inMemory("web-" + UUID.randomUUID());
        server =
                new PayrollWebServer(
                        PayrollServices.forDatabase(
                                database, Files.createTempDirectory("payroll-web-")),
                        0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();

        String home = get("/");
        assertTrue(home.contains("薪酬管理台"));

        String employees = get("/api/employees?include_inactive=true");
        assertTrue(employees.contains("EMP001"));
        assertTrue(employees.contains("\"active\":true"));

        String preview =
                post(
                        "/api/runs/preview",
                        "{\"period\":\"2026-10\",\"scope\":\"全部\",\"adjustments\":[]}");
        assertTrue(preview.contains("\"DRAFT\""));
        long runId = extractRunId(preview);

        String finalized = post("/api/runs/" + runId + "/finalize", "");
        assertTrue(finalized.contains("\"FINALIZED\""));

        String report = post("/api/runs/" + runId + "/send", "{\"confirm\":true}");
        assertTrue(report.contains("\"dry_run\":true"));
        assertTrue(report.contains("\"failure_count\":1"));

        HttpResponse<byte[]> excel =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(baseUrl + "/api/runs/" + runId + "/excel"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, excel.statusCode());
        assertTrue(excel.body().length > 0);

        String runs = get("/api/runs");
        assertTrue(runs.contains("\"SENT\""));
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String post(String path, String body) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null || body.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8));
        HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static long extractRunId(String json) {
        Matcher matcher = Pattern.compile("\"run_id\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), json);
        return Long.parseLong(matcher.group(1));
    }
}
