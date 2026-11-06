package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.web.PayrollWebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollChatImportWebTest {

    private PayrollWebServer server;
    private String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("chat-import-" + UUID.randomUUID());
        server =
                new PayrollWebServer(
                        PayrollServices.forDatabase(
                                database,
                                Files.createTempDirectory("payroll-chat-import-"),
                                new StubChat()),
                        0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("对话接口返回模型回答与工具调用，空问题被拒绝")
    void servesChat() throws Exception {
        String health = get("/api/health");
        assertTrue(health.contains("\"chat_model\":\"stub:test\""));

        String reply = post("/api/chat", "{\"message\":\"张伟的工资\"}");
        assertTrue(reply.contains("\"reply\":\"回答：张伟的工资\""));
        assertTrue(reply.contains("\"calculate_payroll\""));

        HttpResponse<String> empty =
                postRaw(
                        "/api/chat",
                        "{\"message\":\"\"}",
                        "application/json",
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, empty.statusCode());
        assertTrue(empty.body().contains("请输入要咨询的问题"));

        String reset = post("/api/chat/reset", "");
        assertTrue(reset.contains("\"reset\":true"));
    }

    @Test
    @DisplayName("Excel 导入接口落库并返回逐行结果，模板可下载")
    void importsExcelThroughHttp() throws Exception {
        String[] headers = {
            "工号", "姓名", "部门", "岗位", "城市", "邮箱", "基本工资", "岗位津贴",
            "公积金比例", "社保基数", "专项附加扣除", "入职日期", "银行卡号"
        };
        Object[][] rows = {
            {
                "EMP007", "周杰", "研发部", "工程师", "北京", "zhoujie@example.com",
                20000, 1000, 0.12, null, 0, "2026-09-15", ""
            }
        };
        byte[] workbook = ExcelTestWorkbooks.workbook(headers, rows);

        String report = postBytes("/api/import/employees", workbook);
        assertTrue(report.contains("\"success\":1"));
        assertTrue(report.contains("\"failed\":0"));

        String employees = get("/api/employees?include_inactive=true");
        assertTrue(employees.contains("EMP007"));
        assertTrue(employees.contains("周杰"));

        HttpResponse<byte[]> template =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                baseUrl
                                                        + "/api/import/templates/employees"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, template.statusCode());
        assertTrue(template.headers().firstValue("Content-Type").orElse("").contains("spreadsheet"));
        assertTrue(template.body().length > 1000);

        HttpResponse<String> unknown =
                postRaw(
                        "/api/import/bogus",
                        new byte[0],
                        "application/octet-stream",
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("不支持的导入类型"));
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
        HttpResponse<String> response =
                postRaw(
                        path,
                        body,
                        "application/json",
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String postBytes(String path, byte[] bytes) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        path,
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private <T> HttpResponse<T> postRaw(
            String path, String body, String contentType, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        return postRaw(
                path,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                contentType,
                handler);
    }

    private <T> HttpResponse<T> postRaw(
            String path, byte[] body, String contentType, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", contentType)
                        .POST(
                                body.length == 0
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        return client.send(request, handler);
    }

    private static final class StubChat implements PayrollChat {

        @Override
        public Reply chat(String message) {
            if ("触发错误".equals(message)) {
                throw new PayrollException("模型暂不可用");
            }
            return new Reply("回答：" + message, List.of("calculate_payroll"), "stub:test");
        }

        @Override
        public String modelId() {
            return "stub:test";
        }
    }
}
