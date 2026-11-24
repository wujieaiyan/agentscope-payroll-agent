package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.support.LocalAgentRuntimeClient;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 轮次在订阅开始前就已结束（运行期同步执行）时，订阅必须逐帧重放事件日志。
 *
 * <p>回归固定：冷恢复快照只能用于游标早于保留事件的场景，否则会吞掉 tool_result、
 * action_proposal 与 done，前端拿不到工具与提案卡片。
 */
@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatFreshReplayWebTest.SyncStubConfig.class)
@Tag("integration")
class ChatFreshReplayWebTest {

    private static final String ADMIN_PASSWORD = "replay-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-fresh-replay-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.force-password-change", () -> "false");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("新订阅晚于轮次结束：逐帧重放工具、提案与 done，不用快照收口")
    void freshSubscriptionReplaysFinishedTurn() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "重放验证");

        HttpResponse<String> stream = stream(admin, sessionId);
        assertEquals(200, stream.statusCode(), stream.body());
        String body = stream.body();
        assertTrue(body.contains("event:tool_result"), body);
        assertTrue(body.contains("\"duration_ms\":42"), body);
        assertTrue(body.contains("event:action_proposal"), body);
        assertTrue(body.contains("\"trace_count\":1"), body);
        assertFalse(body.contains("\"recovered\":true"), body);

        String turnId = first(body, "\"turn_id\":\"([^\"]+)\"");
        HttpResponse<String> replay = getRaw(
                admin,
                "/api/chat/sessions/" + sessionId + "/turns/" + turnId + "/events?after_seq=0");
        assertEquals(200, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("event:tool_result"), replay.body());
        assertTrue(replay.body().contains("event:action_proposal"), replay.body());
        assertFalse(replay.body().contains("\"recovered\":true"), replay.body());
    }

    private HttpResponse<String> stream(HttpClient client, long sessionId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/api/chat/sessions/" + sessionId + "/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"message\":\"禁用员工 EMP001\"}",
                                        StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response =
                postRaw(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}")
                        .body();
        return Long.parseLong(first(response, "\"id\":(\\d+)"));
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        client,
                        "/api/auth/login",
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpResponse<String> getRaw(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static String first(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-replay-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SyncStubConfig {

        /** 同步执行：提交返回前事件已全部写入总线，订阅一定晚于轮次结束。 */
        @Bean
        @Primary
        AgentRuntimeClient syncRuntimeClient(ChatSessionManager manager) {
            return new LocalAgentRuntimeClient(manager, Runnable::run);
        }

        @Bean
        @Primary
        PayrollChat replayChat() {
            return new ReplayStubChat();
        }

        @Bean
        @Primary
        PayrollChatFactory replayChatFactory() {
            return (sessionId, userId) -> new ReplayStubChat();
        }
    }

    private static final class ReplayStubChat implements PayrollChat {

        private static final String ARGS =
                "{\"employee_id\":\"EMP001\",\"period\":\"2026-10\"}";
        private static final String RESULT =
                """
                {"status":"OK","data":{"employee_id":"EMP001","period":"2026-10","net_pay":21000.50}}
                """;

        @Override
        public PayrollChat.Reply chat(String message) {
            return reply();
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            listener.onStart("stub:replay");
            listener.onAgentStart("assistant", "解析诉求");
            listener.onToolStart("call-1", "calculate_payroll");
            listener.onToolArgs("call-1", ARGS);
            listener.onToolResult("call-1", "calculate_payroll", "SUCCESS", trace());
            listener.onAgentEnd("assistant");
            listener.onText("该操作需要确认后执行。");
            listener.onActionProposal(proposal());
            listener.onDone(reply());
        }

        @Override
        public String modelId() {
            return "stub:replay";
        }

        private static PayrollChat.Reply reply() {
            return new PayrollChat.Reply(
                    "该操作需要确认后执行。",
                    List.of("calculate_payroll", "disable_employee"),
                    "stub:replay",
                    List.of(trace()),
                    null,
                    new ChatRouting(
                            ChatRouting.AGENT,
                            "薪酬助手",
                            "选择智能体",
                            null,
                            null,
                            "stub:replay",
                            "sig",
                            List.of(),
                            0L,
                            12,
                            List.of()),
                    List.of(proposal()),
                    null);
        }

        private static ChatToolTrace trace() {
            return ChatToolTrace.draft(
                    "call-1",
                    "calculate_payroll",
                    "assistant",
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

        private static ActionProposal proposal() {
            LocalDateTime now = LocalDateTime.now();
            String proposalId = UUID.randomUUID().toString();
            return new ActionProposal(
                    proposalId,
                    1L,
                    0L,
                    0L,
                    ChatRouting.AGENT,
                    "disable_employee",
                    "{\"employee_id\":\"EMP001\"}",
                    "禁用员工 EMP001",
                    ActionProposal.RISK_NORMAL,
                    "DEFAULT_MUTATING",
                    ActionProposal.STATUS_PENDING,
                    proposalId,
                    "hash-" + proposalId,
                    now,
                    now.plusMinutes(15),
                    0L,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
