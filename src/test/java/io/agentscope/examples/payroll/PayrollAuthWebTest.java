package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@Import(PayrollAuthWebTest.StubChatConfig.class)
class PayrollAuthWebTest {

    private static final String ADMIN_PASSWORD = "test-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

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
    @DisplayName("未登录被拦截，管理员可建用户，只读角色只能查看")
    void requiresLoginAndEnforcesPermissions() throws Exception {
        HttpClient anonymous = newClient();
        HttpResponse<String> unauthorized =
                getRaw(anonymous, "/api/employees?include_inactive=true");
        assertEquals(401, unauthorized.statusCode(), unauthorized.body());
        assertTrue(unauthorized.body().contains("未登录"), unauthorized.body());

        HttpResponse<String> home = getRaw(anonymous, "/");
        assertEquals(302, home.statusCode(), home.body());
        assertTrue(home.headers().firstValue("Location").orElse("").contains("login.html"));

        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        String me = get(admin, "/api/auth/me");
        assertTrue(me.contains("\"role_code\":\"ADMIN\""), me);
        assertTrue(me.contains("\"user:manage\""), me);

        createViewer(admin, "viewer_perm", "viewer-perm-pass");

        HttpClient viewer = newClient();
        login(viewer, "viewer_perm", "viewer-perm-pass");
        String employees = get(viewer, "/api/employees?include_inactive=true");
        assertTrue(employees.contains("EMP001"), employees);
        assertEquals(403, getRaw(viewer, "/api/admin/users").statusCode());
        assertEquals(403, postRaw(viewer, "/api/employees", "{}").statusCode());
        assertEquals(200, getRaw(viewer, "/api/admin/roles").statusCode());
        assertEquals(200, getRaw(viewer, "/api/admin/departments").statusCode());

        String wrongPassword =
                postRaw(anonymous, "/api/auth/login",
                                "{\"username\":\"viewer_perm\",\"password\":\"bad-pass\"}")
                        .body();
        assertTrue(wrongPassword.contains("用户名或密码错误"), wrongPassword);
    }

    @Test
    @DisplayName("不同用户、不同窗口的会话与消息相互隔离")
    void isolatesChatSessionsPerUserAndWindow() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createViewer(admin, "viewer_chat", "viewer-chat-pass");

        long firstSession = createSession(admin, "管理员会话A");
        long secondSession = createSession(admin, "管理员会话B");

        String adminReply =
                post(admin, "/api/chat/sessions/" + firstSession + "/messages",
                        "{\"message\":\"管理员专属问题\"}");
        assertTrue(adminReply.contains("回答：管理员专属问题"), adminReply);

        String sessions = get(admin, "/api/chat/sessions");
        assertTrue(sessions.contains("管理员会话A"), sessions);
        assertTrue(sessions.contains("管理员会话B"), sessions);

        HttpClient viewer = newClient();
        login(viewer, "viewer_chat", "viewer-chat-pass");
        String viewerSessionsBefore = get(viewer, "/api/chat/sessions");
        assertFalse(viewerSessionsBefore.contains("管理员会话A"), viewerSessionsBefore);

        long viewerSession = createSession(viewer, "访客会话");
        String viewerReply =
                post(viewer, "/api/chat/sessions/" + viewerSession + "/messages",
                        "{\"message\":\"访客专属问题\"}");
        assertTrue(viewerReply.contains("回答：访客专属问题"), viewerReply);

        String viewerMessages =
                get(viewer, "/api/chat/sessions/" + viewerSession + "/messages");
        assertTrue(viewerMessages.contains("访客专属问题"), viewerMessages);
        assertFalse(viewerMessages.contains("管理员专属问题"), viewerMessages);

        String firstMessages =
                get(admin, "/api/chat/sessions/" + firstSession + "/messages");
        assertTrue(firstMessages.contains("管理员专属问题"), firstMessages);
        String secondMessages =
                get(admin, "/api/chat/sessions/" + secondSession + "/messages");
        assertFalse(secondMessages.contains("管理员专属问题"), secondMessages);

        assertEquals(
                400,
                getRaw(admin, "/api/chat/sessions/" + viewerSession + "/messages").statusCode());
        assertEquals(
                400,
                deleteRaw(admin, "/api/chat/sessions/" + viewerSession).statusCode());
    }

    private void createViewer(HttpClient admin, String username, String password)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"测试访客","password":"%s","role_code":"VIEWER"}
                """
                        .formatted(username, password);
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
        assertTrue(created.contains("\"role_code\":\"VIEWER\""), created);
    }

    private long createSession(HttpClient client, String title) throws Exception {
        String response =
                post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(response);
        assertTrue(matcher.find(), response);
        return Long.parseLong(matcher.group(1));
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        client,
                        "/api/auth/login",
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"username\":\"" + username + "\""), response.body());
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

    private HttpResponse<String> deleteRaw(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-auth-");
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

        @Bean
        @Primary
        PayrollChatFactory stubChatFactory() {
            return (sessionId, userId) -> new StubChat();
        }
    }

    private static final class StubChat implements PayrollChat {

        @Override
        public Reply chat(String message) {
            return new Reply("回答：" + message, List.of("calculate_payroll"), "stub:test");
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            listener.onStart("stub:test");
            listener.onText("回答：" + message);
            listener.onDone(
                    new Reply("回答：" + message, List.of("calculate_payroll"), "stub:test"));
        }

        @Override
        public String modelId() {
            return "stub:test";
        }
    }
}
