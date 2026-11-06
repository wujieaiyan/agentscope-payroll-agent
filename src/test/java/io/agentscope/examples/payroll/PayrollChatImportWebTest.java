package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PayrollChatImportWebTest.StubChatConfig.class)
class PayrollChatImportWebTest {

    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-import-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

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
                                                baseUrl()
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

    @Test
    @DisplayName("SSE 流式接口推送思考、工具、回答与错误事件")
    void streamsChatOverSse() throws Exception {
        HttpResponse<String> response =
                postSse("/api/chat/stream", "{\"message\":\"张伟的工资\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .contains("text/event-stream"));

        String body = response.body();
        assertTrue(body.contains("event:start"), body);
        assertTrue(body.contains("event:agent_start"), body);
        assertTrue(body.contains("event:agent_text"), body);
        assertTrue(body.contains("event:agent_end"), body);
        assertTrue(body.contains("event:thinking"), body);
        assertTrue(body.contains("event:phase"), body);
        assertTrue(body.contains("event:tool_start"), body);
        assertTrue(body.contains("event:tool_args"), body);
        assertTrue(body.contains("event:tool_result"), body);
        assertTrue(body.contains("event:text"), body);
        assertTrue(body.contains("event:done"), body);
        assertTrue(body.contains("\"agent\":\"calculator\""), body);
        assertTrue(body.contains("\"agent\":\"validator\""), body);
        assertTrue(body.contains("\"agent\":\"accountant\""), body);
        assertTrue(body.contains("\"reply\":\"回答：张伟的工资\""), body);
        assertTrue(body.contains("\"tools\":[\"calculate_payroll\"]"), body);

        HttpResponse<String> failed = postSse("/api/chat/stream", "{\"message\":\"触发错误\"}");
        assertEquals(200, failed.statusCode(), failed.body());
        assertTrue(failed.body().contains("event:error"), failed.body());
        assertTrue(failed.body().contains("模型暂不可用"), failed.body());

        HttpResponse<String> empty = postSse("/api/chat/stream", "{\"message\":\"\"}");
        assertEquals(200, empty.statusCode(), empty.body());
        assertTrue(empty.body().contains("event:error"), empty.body());
        assertTrue(empty.body().contains("请输入要咨询的问题"), empty.body());

        HttpResponse<String> tooLong =
                postSse("/api/chat/stream", "{\"message\":\"" + "a".repeat(2001) + "\"}");
        assertEquals(200, tooLong.statusCode(), tooLong.body());
        assertTrue(tooLong.body().contains("event:error"), tooLong.body());
        assertTrue(tooLong.body().contains("问题过长"), tooLong.body());
    }

    private HttpResponse<String> postSse(String path, String body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", contentType)
                        .POST(
                                body.length == 0
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        return client.send(request, handler);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-import-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatConfig {

        @Bean
        @Primary
        PayrollChat stubChat() {
            return new StubChat();
        }
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
        public void stream(String message, ChatStreamListener listener) {
            if ("触发错误".equals(message)) {
                throw new PayrollException("模型暂不可用");
            }
            listener.onStart("stub:test");
            listener.onAgentStart("calculator", "① 计算智能体：解析诉求");
            listener.onPhase("模型思考中…");
            listener.onThinking("先确认员工与期间。");
            listener.onToolStart("call-1", "calculate_payroll");
            listener.onToolArgs("call-1", "{\"employee_id\":\"EMP001\"}");
            listener.onToolResult("call-1", "calculate_payroll", "SUCCESS");
            listener.onAgentOutput("calculator", "计算记录：EMP001 2026-10");
            listener.onAgentEnd("calculator");
            listener.onAgentStart("validator", "② 验证智能体：独立复核");
            listener.onAgentOutput("validator", "验证结论：通过");
            listener.onAgentEnd("validator");
            listener.onAgentStart("accountant", "③ 核算智能体：汇总");
            listener.onText("回答：");
            listener.onText(message);
            listener.onAgentEnd("accountant");
            listener.onDone(
                    new Reply("回答：" + message, List.of("calculate_payroll"), "stub:test"));
        }

        @Override
        public String modelId() {
            return "stub:test";
        }
    }
}
