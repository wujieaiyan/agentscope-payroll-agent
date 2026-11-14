package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.service.ActionExecutor;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.support.LocalAgentRuntimeClient;
import io.agentscope.examples.payroll.support.Permissions;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
@Import(ChatActionConfirmWebTest.StubConfig.class)
class ChatActionConfirmWebTest {

    private static final String ADMIN_PASSWORD = "action-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:chat-action-confirm-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.chat.action-confirm.enabled", () -> "true");
    }

    @Autowired
    private H2ChatStore chatStore;

    @Autowired
    private H2ActionProposalStore proposalStore;

    @Autowired
    private StubActionExecutor stubExecutor;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("SSE 推送提案，确认后执行一次并追加操作结果消息")
    void confirmsProposalAndAppendsResultMessage() throws Exception {
        HttpClient admin = newClient();
        long adminId = login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "提案确认");

        HttpResponse<String> stream = stream(admin, sessionId, "禁用员工 EMP001");
        assertEquals(200, stream.statusCode(), stream.body());
        String body = stream.body();
        assertTrue(body.contains("event:action_proposal"), body);
        assertTrue(body.contains("\"status\":\"PENDING\""), body);

        String proposalId = first(body, "\"proposal_id\":\"([^\"]+)\"");
        long messageId = Long.parseLong(first(body, "\"message_id\":(\\d+),\"routing\""));
        assertEquals(adminId, findProposal(proposalId).userId());

        String messages = get(admin, "/api/chat/sessions/" + sessionId + "/messages");
        assertTrue(messages.contains("\"proposal_id\":\"" + proposalId + "\""), messages);
        assertTrue(messages.contains("\"status\":\"PENDING\""), messages);

        String routing =
                get(admin, "/api/chat/sessions/" + sessionId + "/messages/" + messageId + "/routing");
        assertTrue(routing.contains("\"path\":\"AGENT\""), routing);

        String tasks = get(admin, "/api/approval/tasks");
        assertFalse(tasks.contains(proposalId), tasks);

        String confirmed =
                post(admin, "/api/chat/actions/" + proposalId + "/confirm", "{\"decision\":\"CONFIRM\"}");
        assertTrue(confirmed.contains("\"status\":\"EXECUTED\""), confirmed);
        assertEquals(1, stubExecutor.executions.get());

        String repeated =
                post(admin, "/api/chat/actions/" + proposalId + "/confirm", "{\"decision\":\"CONFIRM\"}");
        assertTrue(repeated.contains("\"status\":\"EXECUTED\""), repeated);
        assertEquals(1, stubExecutor.executions.get());

        String after = get(admin, "/api/chat/sessions/" + sessionId + "/messages");
        assertTrue(after.contains("操作结果："), after);
        assertTrue(after.contains("状态：EXECUTED"), after);
        assertTrue(after.contains("\"path\":\"ACTION\""), after);
        assertTrue(after.contains("\"model\":\"action-executor\""), after);
    }

    @Test
    @DisplayName("非发起人查询与确认返回 404，提案状态不变")
    void hidesProposalFromOtherUsers() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "跨用户隔离");
        String proposalId = streamAndProposalId(admin, sessionId);

        createUser(admin, "action_viewer", "action-viewer-pass", "VIEWER");
        HttpClient viewer = newClient();
        login(viewer, "action_viewer", "action-viewer-pass");

        assertEquals(404, getRaw(viewer, "/api/chat/actions/" + proposalId).statusCode());
        assertEquals(
                404,
                postRaw(viewer, "/api/chat/actions/" + proposalId + "/confirm", "{\"decision\":\"CONFIRM\"}")
                        .statusCode());
        assertEquals(ActionProposal.STATUS_PENDING, findProposal(proposalId).status());
    }

    @Test
    @DisplayName("发起人确认前失去权限返回 403 且提案保持 PENDING")
    void rejectsConfirmWithoutPermission() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        long viewerId = createUser(admin, "action_owner", "action-owner-pass", "VIEWER");
        HttpClient owner = newClient();
        login(owner, "action_owner", "action-owner-pass");
        long sessionId = createSession(owner, "权限不足");
        String proposalId = seedProposal(sessionId, viewerId, "disable_employee", future());

        HttpResponse<String> response =
                postRaw(
                        owner,
                        "/api/chat/actions/" + proposalId + "/confirm",
                        "{\"decision\":\"CONFIRM\"}");
        assertEquals(403, response.statusCode(), response.body());
        assertEquals(ActionProposal.STATUS_PENDING, findProposal(proposalId).status());
    }

    @Test
    @DisplayName("过期返回 410，取消返回 CANCELED，删除会话后提案不可见")
    void expiresCancelsAndDeletesWithSession() throws Exception {
        HttpClient admin = newClient();
        long adminId = login(admin, "admin", ADMIN_PASSWORD);
        long sessionId = createSession(admin, "过期与取消");
        String expiredId = seedProposal(sessionId, adminId, "disable_employee", past());
        String cancelId = seedProposal(sessionId, adminId, "disable_employee", future());

        HttpResponse<String> expired =
                postRaw(
                        admin,
                        "/api/chat/actions/" + expiredId + "/confirm",
                        "{\"decision\":\"CONFIRM\"}");
        assertEquals(410, expired.statusCode(), expired.body());
        assertEquals(ActionProposal.STATUS_EXPIRED, findProposal(expiredId).status());

        String canceled =
                post(admin, "/api/chat/actions/" + cancelId + "/confirm", "{\"decision\":\"CANCEL\"}");
        assertTrue(canceled.contains("\"status\":\"CANCELED\""), canceled);
        assertEquals(0, stubExecutor.executions.get());

        delete(admin, "/api/chat/sessions/" + sessionId);
        assertEquals(404, getRaw(admin, "/api/chat/actions/" + cancelId).statusCode());
    }

    private String streamAndProposalId(HttpClient client, long sessionId) throws Exception {
        HttpResponse<String> stream = stream(client, sessionId, "禁用员工 EMP001");
        assertEquals(200, stream.statusCode(), stream.body());
        return first(stream.body(), "\"proposal_id\":\"([^\"]+)\"");
    }

    private HttpResponse<String> stream(HttpClient client, long sessionId, String message)
            throws Exception {
        return client.send(
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
                                        "{\"message\":\"" + message + "\"}",
                                        StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String seedProposal(
            long sessionId, long userId, String toolName, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        String proposalId = UUID.randomUUID().toString();
        ActionProposal proposal =
                new ActionProposal(
                        proposalId,
                        userId,
                        sessionId,
                        0L,
                        ChatRouting.AGENT,
                        toolName,
                        "{\"employee_id\":\"EMP001\"}",
                        "禁用员工 EMP001",
                        ActionProposal.RISK_NORMAL,
                        "DEFAULT_MUTATING",
                        ActionProposal.STATUS_PENDING,
                        proposalId,
                        "hash-" + proposalId,
                        now,
                        expiresAt,
                        0L,
                        null,
                        null,
                        null,
                        null);
        chatStore.appendAssistant(
                sessionId,
                "该操作需要确认后执行。",
                List.of(toolName),
                "stub:proposal",
                List.of(),
                new ChatRouting(
                        ChatRouting.AGENT,
                        "薪酬助手",
                        "选择智能体",
                        null,
                        null,
                        "stub:proposal",
                        "sig",
                        List.of(),
                        0L,
                        12,
                        List.of()),
                List.of(proposal));
        return proposalId;
    }

    private ActionProposal findProposal(String proposalId) throws Exception {
        return proposalStore
                .find(proposalId)
                .orElseThrow(() -> new AssertionError("提案不存在：" + proposalId));
    }

    private long createUser(HttpClient admin, String username, String password, String role)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"提案用户","password":"%s","role_code":"%s"}
                """
                        .formatted(username, password, role);
        String created = post(admin, "/api/admin/users", body);
        return Long.parseLong(first(created, "\"id\":(\\d+)"));
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response = post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        return Long.parseLong(first(response, "\"id\":(\\d+)"));
    }

    private long login(HttpClient client, String username, String password) throws Exception {
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
        return Long.parseLong(first(response.body(), "\"id\":(\\d+)"));
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
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void delete(HttpClient client, String path) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
    }

    private static String first(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    private static LocalDateTime future() {
        return LocalDateTime.now().plusMinutes(10);
    }

    private static LocalDateTime past() {
        return LocalDateTime.now().minusMinutes(1);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-chat-action-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        @Primary
        AgentRuntimeClient stubRuntimeClient(
                ChatSessionManager manager, @Qualifier("chatStreamExecutor") Executor executor) {
            return new LocalAgentRuntimeClient(manager, executor);
        }

        @Bean
        @Primary
        StubActionExecutor stubActionExecutor() {
            return new StubActionExecutor();
        }

        @Bean
        @Primary
        PayrollChat stubProposalChat() {
            return new ProposalStubChat(1L);
        }

        @Bean
        @Primary
        PayrollChatFactory stubProposalChatFactory() {
            return (sessionId, userId) -> new ProposalStubChat(userId);
        }
    }

    static final class StubActionExecutor implements ActionExecutor {

        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String requiredPermission(String toolName) {
            return "disable_employee".equals(toolName)
                    ? Permissions.EMPLOYEE_WRITE
                    : Permissions.CHAT_USE;
        }

        @Override
        public Object execute(ActionProposal proposal, long userId) {
            executions.incrementAndGet();
            return Map.of("status", "OK", "user_id", userId);
        }
    }

    private static final class ProposalStubChat implements PayrollChat {

        private final long userId;

        private ProposalStubChat(long userId) {
            this.userId = userId;
        }

        @Override
        public Reply chat(String message) {
            return reply();
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            ActionProposal proposal = proposal();
            listener.onStart("stub:proposal");
            listener.onAgentStart("assistant", "解析诉求");
            listener.onText("需要你确认后才能继续。");
            listener.onActionProposal(proposal);
            listener.onAgentEnd("assistant");
            listener.onDone(reply(proposal));
        }

        @Override
        public String modelId() {
            return "stub:proposal";
        }

        private Reply reply() {
            return reply(proposal());
        }

        private Reply reply(ActionProposal proposal) {
            return new Reply(
                    "需要你确认后才能继续。",
                    List.of(proposal.toolName()),
                    "stub:proposal",
                    List.of(),
                    null,
                    new ChatRouting(
                            ChatRouting.AGENT,
                            "薪酬助手",
                            "选择智能体",
                            null,
                            null,
                            "stub:proposal",
                            "sig",
                            List.of(),
                            0L,
                            12,
                            List.of()),
                    List.of(proposal),
                    null);
        }

        private ActionProposal proposal() {
            LocalDateTime now = LocalDateTime.now();
            String proposalId = UUID.randomUUID().toString();
            return new ActionProposal(
                    proposalId,
                    userId,
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
