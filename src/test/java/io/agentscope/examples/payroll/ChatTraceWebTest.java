package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
@Import(ChatTraceWebTest.StubChatConfig.class)
class ChatTraceWebTest {

    private static final String ADMIN_PASSWORD = "trace-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-trace-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

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
    @DisplayName("SSE 推送工具金额，落库后可按消息查询计算依据，跨用户不可见")
    void exposesTracesForAssistantMessage() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "追溯验证");

        HttpResponse<String> stream =
                admin.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                baseUrl()
                                                        + "/api/chat/sessions/"
                                                        + sessionId
                                                        + "/stream"))
                                .header("Content-Type", "application/json")
                                .header("Accept", "text/event-stream")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"message\":\"张伟的工资\"}",
                                                StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, stream.statusCode(), stream.body());
        String body = stream.body();
        assertTrue(body.contains("event:tool_result"), body);
        assertTrue(body.contains("\"duration_ms\":42"), body);
        assertTrue(body.contains("\"amounts\":{\"net_pay\":21000.50}"), body);
        assertTrue(body.contains("\"trace_count\":1"), body);

        Matcher matcher = Pattern.compile("\"message_id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        long messageId = Long.parseLong(matcher.group(1));

        String traces =
                get(admin, "/api/chat/sessions/" + sessionId + "/messages/" + messageId + "/traces");
        assertTrue(traces.contains("\"tool_name\":\"calculate_payroll\""), traces);
        assertTrue(traces.contains("\"employees\":[\"EMP001\"]"), traces);
        assertTrue(traces.contains("\"period\":\"2026-10\""), traces);
        assertTrue(traces.contains("\"net_pay\":21000.50"), traces);
        assertTrue(traces.contains("\"args_json\""), traces);
        assertTrue(traces.contains("\"result_json\""), traces);

        String messages = get(admin, "/api/chat/sessions/" + sessionId + "/messages");
        assertTrue(messages.contains("张伟实发工资 21000.50 元"), messages);
        assertTrue(messages.contains("\"id\":" + messageId), messages);

        createViewer(admin, "trace_viewer", "trace-viewer-pass");
        HttpClient viewer = newClient();
        login(viewer, "trace_viewer", "trace-viewer-pass");
        assertEquals(
                400,
                getRaw(viewer, "/api/chat/sessions/" + sessionId + "/messages/" + messageId + "/traces")
                        .statusCode());

        delete(admin, "/api/chat/sessions/" + sessionId);
        assertEquals(
                400,
                getRaw(admin, "/api/chat/sessions/" + sessionId + "/messages/" + messageId + "/traces")
                        .statusCode());
    }

    private void createViewer(HttpClient admin, String username, String password)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"追溯访客","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response = post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(response);
        assertTrue(matcher.find(), response);
        return Long.parseLong(matcher.group(1));
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
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void delete(HttpClient client, String path) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-trace-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatConfig {

        @Bean
        @Primary
        PayrollChat stubChat() {
            return new TraceStubChat();
        }

        @Bean
        @Primary
        PayrollChatFactory stubChatFactory() {
            return (sessionId, userId) -> new TraceStubChat();
        }
    }

    private static final class TraceStubChat implements PayrollChat {

        private static final String ARGS =
                "{\"employee_id\":\"EMP001\",\"period\":\"2026-10\"}";
        private static final String RESULT =
                """
                {"status":"OK","data":{"employee_id":"EMP001","period":"2026-10","net_pay":21000.50}}
                """;

        @Override
        public Reply chat(String message) {
            return reply();
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            ChatToolTrace trace = trace();
            listener.onStart("stub:trace");
            listener.onAgentStart("calculator", "① 计算智能体：解析诉求");
            listener.onToolStart("call-1", "calculate_payroll");
            listener.onToolArgs("call-1", ARGS);
            listener.onToolResult("call-1", "calculate_payroll", "SUCCESS", trace);
            listener.onAgentEnd("calculator");
            listener.onText("张伟实发工资 21000.50 元");
            listener.onDone(reply());
        }

        @Override
        public String modelId() {
            return "stub:trace";
        }

        private static Reply reply() {
            return new Reply(
                    "张伟实发工资 21000.50 元",
                    List.of("calculate_payroll"),
                    "stub:trace",
                    List.of(trace()));
        }

        private static ChatToolTrace trace() {
            return ChatToolTrace.draft(
                    "call-1",
                    "calculate_payroll",
                    "calculator",
                    ARGS,
                    RESULT,
                    false,
                    "SUCCESS",
                    42,
                    List.of("EMP001"),
                    "2026-10",
                    "研发部",
                    Map.of("net_pay", new BigDecimal("21000.50")));
        }
    }
}
