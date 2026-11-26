package io.agentscope.payroll.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.domain.ExtractionMeta;
import io.agentscope.examples.payroll.kb.parse.ChatExtraction;
import io.agentscope.examples.payroll.kb.parse.KbChatParseResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.agentscope.examples.payroll.kb.parse.KbParseClient;
import io.agentscope.examples.payroll.kb.parse.KbParseException;
import io.agentscope.examples.payroll.kb.parse.KbParseJobResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbParseSettings;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;
import io.agentscope.examples.payroll.service.InternalAuth;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 远程解析客户端：HMAC 头、产物共享卷读取与本地缓存命中。 */
class KbParseClientTest {

    private static final String TOKEN = "client-parse-token";

    @TempDir Path tempDir;

    private HttpServer server;
    private final AtomicInteger parseCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/kb/parse",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!authorized(exchange, "/internal/kb/parse", body)) {
                        respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
                        return;
                    }
                    parseCalls.incrementAndGet();
                    try {
                        respondParse(exchange, body);
                    } catch (IOException e) {
                        respond(exchange, 500, "{\"code\":\"WRITE_FAILED\"}");
                    }
                });
        server.createContext(
                "/internal/kb/chat/parse",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!authorized(exchange, "/internal/kb/chat/parse", body)) {
                        respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
                        return;
                    }
                    KbChatParseResponse response =
                            new KbChatParseResponse(
                                    "chat 解析结果",
                                    ExtractionMeta.nativeParse("pdf", "0".repeat(64), 5, 6));
                    respond(exchange, 200, KbParseArtifacts.mapper().writeValueAsString(response));
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("remote 模式缺少 token 时客户端构造失败，避免带病启动")
    void remoteWithoutTokenFailsFast() {
        KbParseSettings settings = new KbParseSettings();
        settings.setMode("remote");
        settings.setUrl("http://127.0.0.1:1");
        settings.setToken(" ");

        assertThrows(IllegalStateException.class, () -> new KbParseClient(settings));
    }

    @Test
    @DisplayName("签名不匹配时服务端 401，客户端按鉴权失败直接报错")
    void unauthorizedFailsFast() throws IOException {
        KbParseClient client = client("wrong-token");

        assertThrows(KbParseException.class, () -> client.parse(request(1L), null, () -> false));
        assertEquals(0, parseCalls.get());
    }

    @Test
    @DisplayName("服务端产物写入共享卷后客户端读取全文，第二次直接命中本地缓存")
    void readsArtifactAndCachesLocally() throws IOException {
        KbParseClient client = client(TOKEN);
        KbParseRequest request = request(2L);

        KbParsedDocument first = client.parse(request, null, () -> false);
        assertTrue(first.text().contains("remote parse"));
        assertEquals(1, parseCalls.get());

        KbParsedDocument second = client.parse(request, null, () -> false);
        assertEquals(first.text(), second.text());
        assertEquals(1, parseCalls.get());
    }

    @Test
    @DisplayName("对话附件经 base64 往返解析")
    void parsesChatAttachment() throws IOException {
        KbParseClient client = client(TOKEN);
        Path file = tempDir.resolve("attach.pdf");
        Files.write(file, "%PDF-1.4 stub".getBytes(StandardCharsets.UTF_8));

        ChatExtraction extraction = client.parseChat(file, "pdf");

        assertEquals("chat 解析结果", extraction.text());
        assertEquals("native/pdf", extraction.meta().engine());
    }

    private KbParseClient client(String token) {
        KbParseSettings settings = new KbParseSettings();
        settings.setMode("remote");
        settings.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setToken(token);
        settings.setArtifactDir(tempDir.resolve("artifacts").toString());
        settings.setMaxPages(200);
        settings.setQueueWaitMs(500);
        settings.setMaxRetries(0);
        settings.setTimeoutMs(3000);
        return new KbParseClient(settings);
    }

    private KbParseRequest request(long documentId) throws IOException {
        Path source = tempDir.resolve("src-" + documentId + ".pdf");
        Files.write(source, ("payload-" + documentId).getBytes(StandardCharsets.UTF_8));
        return new KbParseRequest(
                documentId, source, "doc/" + documentId + ".pdf", "pdf", "application/pdf", ParseConfig.defaults());
    }

    private void respondParse(HttpExchange exchange, String body) throws IOException {
        io.agentscope.examples.payroll.kb.parse.KbParseJobRequest job =
                KbParseArtifacts.mapper().readValue(body, io.agentscope.examples.payroll.kb.parse.KbParseJobRequest.class);
        // 生产环境 parser 与 Web 共享源文件卷，哈希天然一致；本地用同一临时文件模拟。
        Path source = tempDir.resolve("src-" + job.documentId() + ".pdf");
        String sha =
                Files.isRegularFile(source) ? KbParseArtifacts.sha256(source) : "b".repeat(64);
        ParseConfig config = job.config() == null ? ParseConfig.defaults() : job.config();
        String profile = KbParseArtifacts.profile(config, job.maxPages());
        KbParsedDocument document =
                new KbParsedDocument("remote parse body", 1, List.of(), List.of());
        KbParseArtifacts.Artifact artifact =
                new KbParseArtifacts.Artifact(
                        job.documentId(),
                        sha,
                        profile,
                        job.ext(),
                        1,
                        document.text().length(),
                        0,
                        "native",
                        3L,
                        System.currentTimeMillis(),
                        document);
        Path written = KbParseArtifacts.writeAtomic(tempDir.resolve("artifacts"), artifact);
        String key = tempDir.resolve("artifacts").relativize(written).toString().replace('\\', '/');
        KbParseJobResponse response =
                new KbParseJobResponse(key, job.documentId(), sha, profile, 1, document.text().length(), 0, "native", 3L, false);
        respond(exchange, 200, KbParseArtifacts.mapper().writeValueAsString(response));
    }

    /** artifactKey 由服务端给出；客户端按 base 解析，必须与实际写入路径一致。 */
    private boolean authorized(HttpExchange exchange, String path, String body) {
        return InternalAuth.verify(
                TOKEN,
                "POST",
                path,
                exchange.getRequestHeaders().getFirst("X-Payroll-Timestamp"),
                exchange.getRequestHeaders().getFirst("X-Payroll-Internal"),
                body,
                120);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
