package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
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
@Import(ChatFeedbackWebTest.StubChatConfig.class)
class ChatFeedbackWebTest {

    private static final String ADMIN_PASSWORD = "feedback-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-feedback-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.team.mode", () -> "STATIC");
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("反馈可提交、覆盖、查询，校验标签与评分，跨用户 403")
    void recordsFeedbackWithOwnershipAndValidation() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "反馈验证");
        long messageId = streamAndReadMessageId(admin, sessionId);

        HttpResponse<String> up =
                postRaw(
                        admin,
                        feedbackPath(sessionId, messageId),
                        "{\"rating\":\"up\"}");
        assertEquals(200, up.statusCode(), up.body());
        assertTrue(up.body().contains("\"rating\":\"UP\""), up.body());
        long feedbackId = parseId(up.body());

        HttpResponse<String> down =
                postRaw(
                        admin,
                        feedbackPath(sessionId, messageId),
                        "{\"rating\":\"DOWN\",\"tags\":[\"WRONG_AMOUNT\",\"SLOW\"],"
                                + "\"comment\":\"金额不对\"}");
        assertEquals(200, down.statusCode(), down.body());
        assertTrue(down.body().contains("\"rating\":\"DOWN\""), down.body());
        assertTrue(down.body().contains("\"WRONG_AMOUNT\""), down.body());
        assertTrue(down.body().contains("\"comment\":\"金额不对\""), down.body());
        assertEquals(feedbackId, parseId(down.body()));

        HttpResponse<String> loaded = get(admin, feedbackPath(sessionId, messageId));
        assertEquals(200, loaded.statusCode(), loaded.body());
        assertTrue(loaded.body().contains("\"rating\":\"DOWN\""), loaded.body());
        assertTrue(loaded.body().contains("\"tags\":[\"WRONG_AMOUNT\",\"SLOW\"]"), loaded.body());

        assertEquals(
                400,
                postRaw(
                                admin,
                                feedbackPath(sessionId, messageId),
                                "{\"rating\":\"MAYBE\"}")
                        .statusCode());
        assertEquals(
                400,
                postRaw(
                                admin,
                                feedbackPath(sessionId, messageId),
                                "{\"rating\":\"DOWN\",\"tags\":[\"UNKNOWN\"]}")
                        .statusCode());
        assertEquals(
                400,
                postRaw(admin, feedbackPath(sessionId, messageId - 1), "{\"rating\":\"UP\"}")
                        .statusCode());

        createViewer(admin, "feedback_viewer", "feedback-viewer-pass");
        HttpClient viewer = newClient();
        login(viewer, "feedback_viewer", "feedback-viewer-pass");
        assertEquals(
                403,
                postRaw(
                                viewer,
                                feedbackPath(sessionId, messageId),
                                "{\"rating\":\"UP\"}")
                        .statusCode());
        assertEquals(403, get(viewer, feedbackPath(sessionId, messageId)).statusCode());
    }

    @Test
    @DisplayName("按消息读取团队运行、节点、trace 引用与当前反馈，跨用户 403")
    void exposesTeamView() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "团队追溯");
        long messageId = streamAndReadMessageId(admin, sessionId);

        String team = get(admin, teamPath(sessionId, messageId)).body();
        assertTrue(team.contains("\"mode\":\"STATIC\""), team);
        assertTrue(team.contains("\"primary_run\":{"), team);
        assertTrue(team.contains("\"runs\":[{"), team);
        assertTrue(team.contains("\"stage_key\":\"calculator\""), team);
        assertTrue(team.contains("\"tool_calls\":1"), team);
        assertTrue(team.contains("\"node_id\":\"calculator\""), team);

        assertEquals(
                200,
                postRaw(
                                admin,
                                feedbackPath(sessionId, messageId),
                                "{\"rating\":\"DOWN\",\"tags\":[\"INCOMPLETE\"]}")
                        .statusCode());
        String withFeedback = get(admin, teamPath(sessionId, messageId)).body();
        assertTrue(withFeedback.contains("\"feedback\":{"), withFeedback);
        assertTrue(withFeedback.contains("\"rating\":\"DOWN\""), withFeedback);

        createViewer(admin, "team_viewer", "team-viewer-pass");
        HttpClient viewer = newClient();
        login(viewer, "team_viewer", "team-viewer-pass");
        assertEquals(403, get(viewer, teamPath(sessionId, messageId)).statusCode());
    }

    @Test
    @DisplayName("评分统计接口仅 agent:manage 可读，返回窗口与评分字段")
    void exposesAgentStatsForManagersOnly() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        HttpResponse<String> stats = get(admin, "/api/admin/agent-stats");
        assertEquals(200, stats.statusCode(), stats.body());
        assertTrue(stats.body().contains("\"window_days\":30"), stats.body());
        assertTrue(stats.body().contains("\"agents\":["), stats.body());
        assertTrue(stats.body().contains("\"record_count\":"), stats.body());
        assertTrue(stats.body().contains("\"final_score\":"), stats.body());

        createViewer(admin, "stats_viewer", "stats-viewer-pass");
        HttpClient viewer = newClient();
        login(viewer, "stats_viewer", "stats-viewer-pass");
        assertEquals(403, get(viewer, "/api/admin/agent-stats").statusCode());
    }

    private String teamPath(long sessionId, long messageId) {
        return "/api/chat/sessions/" + sessionId + "/messages/" + messageId + "/team";
    }

    private String feedbackPath(long sessionId, long messageId) {
        return "/api/chat/sessions/"
                + sessionId
                + "/messages/"
                + messageId
                + "/feedback";
    }

    private long streamAndReadMessageId(HttpClient client, long sessionId) throws Exception {
        HttpResponse<String> stream =
                client.send(
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
        Matcher matcher = Pattern.compile("\"message_id\":(\\d+)").matcher(stream.body());
        assertTrue(matcher.find(), stream.body());
        return Long.parseLong(matcher.group(1));
    }

    private static long parseId(String body) {
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private void createViewer(HttpClient admin, String username, String password)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"反馈访客","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response = post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        return parseId(response);
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

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
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
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-feedback-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatConfig {

        @Bean
        @Primary
        PayrollChat stubChat() {
            return new FeedbackStubChat();
        }

        @Bean
        @Primary
        PayrollChatFactory stubChatFactory() {
            return (sessionId, userId) -> new FeedbackStubChat();
        }
    }

    private static final class FeedbackStubChat implements PayrollChat {

        private TraceScope scope;

        @Override
        public void useRunScope(TraceScope scope) {
            this.scope = scope;
        }

        @Override
        public List<StageRun> stageRuns() {
            return List.of(
                    new StageRun(
                            "calculator",
                            "calculator",
                            "PayrollCalculatorAgent",
                            "CALCULATOR",
                            "SUCCESS",
                            1,
                            0,
                            null,
                            12,
                            50,
                            null));
        }

        @Override
        public Reply chat(String message) {
            return reply();
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            listener.onStart("stub:feedback");
            listener.onText("张伟实发工资 21000.50 元");
            listener.onDone(reply());
        }

        @Override
        public String modelId() {
            return "stub:feedback";
        }

        private Reply reply() {
            TraceScope runScope = scope == null ? TraceScope.none() : scope;
            ChatToolTrace trace =
                    ChatToolTrace.draft(
                                    "call-1",
                                    "calculate_payroll",
                                    "calculator",
                                    "{}",
                                    "{\"status\":\"OK\"}",
                                    false,
                                    "SUCCESS",
                                    42,
                                    List.of("EMP001"),
                                    "2026-10",
                                    "研发部",
                                    Map.of())
                            .withScope(
                                    new TraceScope(
                                            runScope.runId(),
                                            runScope.planId(),
                                            "calculator",
                                            "PayrollCalculatorAgent",
                                            "CALCULATOR",
                                            runScope.mode(),
                                            "calculator"));
            return new Reply(
                    "张伟实发工资 21000.50 元",
                    List.of("calculate_payroll"),
                    "stub:feedback",
                    List.of(trace));
        }
    }
}
