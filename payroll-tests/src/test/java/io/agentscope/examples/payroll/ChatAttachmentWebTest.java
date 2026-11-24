package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.support.LocalAgentRuntimeClient;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
@Import(ChatAttachmentWebTest.StubChatConfig.class)
@Tag("integration")
class ChatAttachmentWebTest {

    private static final String ADMIN_PASSWORD = "attachment-admin-pass";
    private static final Path OUTPUT_DIR = tempDir("payroll-chat-attachment-out-");
    private static final Path ATTACHMENT_DIR = tempDir("payroll-chat-attachment-files-");
    private static final String DB_URL =
            "jdbc:h2:mem:chat-attachment-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_ATTACHMENT_TEXT = new AtomicReference<>();

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.team.mode", () -> "STATIC");
        registry.add("payroll.chat.attachment.dir", () -> ATTACHMENT_DIR.toString());
        registry.add("payroll.chat.attachment.max-files", () -> "2");
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetRecorder() {
        LAST_MESSAGE.set(null);
        LAST_ATTACHMENT_TEXT.set(null);
    }

    @Test
    @DisplayName("上传 docx 后发送：附件文本进入模型输入、历史回显引用并可下载")
    void uploadsAndInjectsAttachment() throws Exception {
        HttpClient client = newClient();
        login(client);
        long sessionId = createSession(client, "附件验收");
        byte[] document = docx(
                "十月份工资说明：全勤奖 800 元",
                "以下为附件正文，用于验证附件内容进入模型输入。");

        HttpResponse<String> uploaded =
                upload(client, sessionId, "notes.docx", document);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("\"status\":\"PARSING\""), uploaded.body());
        assertTrue(uploaded.body().contains("\"name\":\"notes.docx\""), uploaded.body());
        long attachmentId = parseField(uploaded.body(), "id");

        String pending = awaitReady(client, sessionId, attachmentId);
        assertTrue(pending.contains("\"name\":\"notes.docx\""), pending);
        assertTrue(parseField(pending, "text_chars") > 0, pending);

        String preview =
                get(client, attachmentsPath(sessionId) + "/" + attachmentId + "/text").body();
        assertTrue(preview.contains("十月份工资说明"), preview);

        HttpResponse<String> stream = stream(client, sessionId, attachmentId);
        assertEquals(200, stream.statusCode(), stream.body());
        assertTrue(stream.body().contains("event:done"), stream.body());

        String attachmentText = LAST_ATTACHMENT_TEXT.get();
        assertNotNull(attachmentText, "附件文本未进入模型输入");
        assertTrue(attachmentText.contains("### 附件 1：notes.docx"), attachmentText);
        assertTrue(attachmentText.contains("十月份工资说明：全勤奖 800 元"), attachmentText);
        assertTrue(attachmentText.contains("以下为附件正文"), attachmentText);
        assertEquals("总结一下附件内容", LAST_MESSAGE.get());

        String messages = get(client, "/api/chat/sessions/" + sessionId + "/messages").body();
        assertTrue(messages.contains("\"name\":\"notes.docx\""), messages);
        assertTrue(messages.contains("\"status\":\"READY\""), messages);

        HttpResponse<byte[]> file =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                baseUrl()
                                                        + attachmentsPath(sessionId)
                                                        + "/"
                                                        + attachmentId
                                                        + "/file"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, file.statusCode());
        assertArrayEquals(document, file.body());
        assertTrue(
                file.headers()
                        .firstValue("Content-Disposition")
                        .orElse("")
                        .startsWith("attachment"));

        assertEquals(
                "[]", dataOf(get(client, attachmentsPath(sessionId)).body()), "发送后待发送列表应清空");
    }

    @Test
    @DisplayName("已发送附件不能重复提交；不支持的类型与待发送删除有明确反馈")
    void rejectsUnsupportedAndDeletedAttachment() throws Exception {
        HttpClient client = newClient();
        login(client);
        long sessionId = createSession(client, "附件校验");

        HttpResponse<String> unsupported =
                upload(client, sessionId, "policy.txt", "plain text".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, unsupported.statusCode(), unsupported.body());
        assertTrue(unsupported.body().contains("暂不支持"), unsupported.body());

        long draftId =
                parseField(
                        upload(
                                        client,
                                        sessionId,
                                        "draft.docx",
                                        docx("草稿正文"))
                                .body(),
                        "id");
        awaitReady(client, sessionId, draftId);
        assertEquals(
                200,
                delete(client, attachmentsPath(sessionId) + "/" + draftId).statusCode());
        assertEquals("[]", dataOf(get(client, attachmentsPath(sessionId)).body()));

        long sentId =
                parseField(
                        upload(
                                        client,
                                        sessionId,
                                        "sent.docx",
                                        docx("已发送正文"))
                                .body(),
                        "id");
        awaitReady(client, sessionId, sentId);
        assertEquals(200, stream(client, sessionId, sentId).statusCode());

        HttpResponse<String> again = stream(client, sessionId, sentId);
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("已发送"), again.body());
    }

    private HttpResponse<String> stream(HttpClient client, long sessionId, long attachmentId)
            throws Exception {
        String body = "{\"message\":\"总结一下附件内容\",\"attachment_ids\":[" + attachmentId + "]}";
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/api/chat/sessions/" + sessionId + "/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String attachmentsPath(long sessionId) {
        return "/api/chat/sessions/" + sessionId + "/attachments";
    }

    /** 上传接口异步返回 PARSING；等到该附件由解析队列落为 READY 后再继续。 */
    private String awaitReady(HttpClient client, long sessionId, long attachmentId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = get(client, attachmentsPath(sessionId)).body();
            for (String item : body.split("\\{")) {
                if (!item.contains("\"id\":" + attachmentId + ",")) {
                    continue;
                }
                if (item.contains("\"status\":\"READY\"")) {
                    return body;
                }
                if (item.contains("\"status\":\"FAILED\"")) {
                    throw new AssertionError("附件解析失败：" + item);
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("附件未在超时内完成解析：" + body);
    }

    private HttpResponse<String> upload(
            HttpClient client, long sessionId, String name, byte[] content) throws Exception {
        String boundary = "----payroll-attachment-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(
                ("--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"files\"; filename=\""
                                + name
                                + "\"\r\n"
                                + "Content-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + attachmentsPath(sessionId)))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private long createSession(HttpClient client, String title) throws Exception {
        HttpResponse<String> response = post(client, "/api/chat/sessions", "{\"title\":\"" + title + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        return parseField(response.body(), "id");
    }

    private static long parseField(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private static String dataOf(String body) {
        Matcher matcher = Pattern.compile("\"data\":(\\[.*?\\])(?=,\"message\"|\\})").matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private void login(HttpClient client) throws Exception {
        HttpResponse<String> response =
                post(
                        client,
                        "/api/auth/login",
                        "{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}");
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

    private HttpResponse<String> delete(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试目录", e);
        }
    }

    private static byte[] docx(String... paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(text);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatConfig {

        @Bean
        @Primary
        AgentRuntimeClient stubRuntimeClient(
                ChatSessionManager manager, @Qualifier("chatStreamExecutor") Executor executor) {
            return new LocalAgentRuntimeClient(manager, executor);
        }

        @Bean
        @Primary
        PayrollChatFactory stubChatFactory() {
            return (sessionId, userId) -> new RecordingChat();
        }
    }

    private static final class RecordingChat implements PayrollChat {

        @Override
        public Reply chat(String message) {
            return reply();
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            stream(message, null, listener);
        }

        @Override
        public void stream(
                String message, String attachmentText, ChatStreamListener listener) {
            LAST_MESSAGE.set(message);
            LAST_ATTACHMENT_TEXT.set(attachmentText);
            listener.onStart(modelId());
            listener.onText("附件已读取");
            listener.onDone(reply());
        }

        @Override
        public String modelId() {
            return "stub:attachment";
        }

        private Reply reply() {
            return new Reply("附件已读取", List.of(), "stub:attachment");
        }
    }
}
