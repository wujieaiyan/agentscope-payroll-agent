package io.agentscope.examples.payroll.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.db.ModelCredentialStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelConnectionTesterTest {

    private PayrollDatabase database;
    private ModelCredentialService credentials;
    private FakeServer server;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("tester-" + UUID.randomUUID());
        credentials =
                new ModelCredentialService(
                        new ModelCredentialStore(database),
                        ModelCredentialCipher.of(
                                "0123456789abcdef0123456789abcdef"
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        database.close();
    }

    @Test
    @DisplayName("Ollama 测试读取已加载模型与能力")
    void ollamaProbeReportsCapabilities() throws IOException {
        server = new FakeServer();
        server.tags =
                """
                {"models":[{"name":"qwen2.5:3b-instruct"},{"name":"llama3:8b"}]}
                """;
        server.show = """
                {"capabilities":["completion","tools"]}
                """;
        ModelConnectionTester tester = testerForServer();

        ModelConnectionTester.Probe probe = tester.test(ollama("qwen2.5:3b-instruct"));

        assertTrue(probe.ok(), probe.message());
        assertTrue(probe.capabilities().contains("tools"));
        assertTrue(probe.models().contains("llama3:8b"));
        assertTrue(server.tagsReads.get() >= 1);
    }

    @Test
    @DisplayName("Ollama 未拉取模型时返回未加载提示")
    void ollamaMissingModelFails() throws IOException {
        server = new FakeServer();
        server.tags = """
                {"models":[{"name":"llama3:8b"}]}
                """;
        ModelConnectionTester tester = testerForServer();

        ModelConnectionTester.Probe probe = tester.test(ollama("qwen2.5:3b-instruct"));

        assertFalse(probe.ok());
        assertEquals(ModelConfigException.UNREACHABLE, probe.code());
        assertTrue(probe.message().contains("未加载"), probe.message());
    }

    @Test
    @DisplayName("OpenAI 兼容端点优先用 /models 探测")
    void openAiProbeUsesModelList() throws IOException {
        server = new FakeServer();
        server.modelsStatus = 200;
        server.models = """
                {"data":[{"id":"gpt-x"},{"id":"gpt-y"}]}
                """;
        ModelConnectionTester tester = testerForServer();
        credentials.save("openai", "sk-test", "admin");

        ModelConnectionTester.Probe probe =
                tester.test(openAi("openai", "cred-openai", ""));

        assertTrue(probe.ok(), probe.message());
        assertEquals(List.of("gpt-x", "gpt-y"), probe.models());
        assertEquals("Bearer sk-test", server.lastAuthorization.get());
    }

    @Test
    @DisplayName("OpenAI 密钥被拒绝时返回缺少密钥")
    void openAiUnauthorizedFails() throws IOException {
        server = new FakeServer();
        server.modelsStatus = 401;
        ModelConnectionTester tester = testerForServer();
        credentials.save("openai", "sk-bad", "admin");

        ModelConnectionTester.Probe probe =
                tester.test(openAi("openai", "cred-openai", ""));

        assertFalse(probe.ok());
        assertEquals(ModelConfigException.CREDENTIAL_MISSING, probe.code());
    }

    @Test
    @DisplayName("/models 不可用时回落到 1 token 对话")
    void openAiFallsBackToChat() throws IOException {
        server = new FakeServer();
        server.modelsStatus = 404;
        server.chatStatus = 200;
        ModelConnectionTester tester = testerForServer();
        credentials.save("openai", "sk-test", "admin");

        ModelConnectionTester.Probe probe =
                tester.test(openAi("openai", "cred-openai", ""));

        assertTrue(probe.ok(), probe.message());
        assertTrue(probe.message().contains("对话"), probe.message());
        assertTrue(server.chatCalls > 0);
    }

    @Test
    @DisplayName("出网策略在建立连接前生效")
    void outboundPolicyBlocksProbe() {
        ModelConnectionTester tester =
                new ModelConnectionTester(new ModelOutboundPolicy(List.of()), credentials);

        ModelConnectionTester.Probe probe =
                tester.test(
                        new ModelConnection(
                                "remote",
                                "远端",
                                ModelConnection.PROVIDER_OLLAMA,
                                "http://10.0.0.9:11434",
                                "qwen2.5:3b-instruct",
                                "",
                                "",
                                8192,
                                1024,
                                0.2,
                                120,
                                List.of(),
                                true,
                                null,
                                "",
                                ModelConnection.ORIGIN_STORED));

        assertFalse(probe.ok());
        assertEquals(ModelConfigException.OUTBOUND_BLOCKED, probe.code());
    }

    @Test
    @DisplayName("输入密钥不会出现在探针结果与提示中")
    void probeNeverEchoesKey() throws IOException {
        server = new FakeServer();
        server.modelsStatus = 401;
        ModelConnectionTester tester = testerForServer();
        credentials.save("openai", "sk-very-secret", "admin");

        ModelConnectionTester.Probe probe =
                tester.test(openAi("openai", "cred-openai", ""));

        assertFalse(probe.message().contains("sk-very-secret"), probe.message());
    }

    private ModelConnectionTester testerForServer() {
        return new ModelConnectionTester(
                new ModelOutboundPolicy(List.of("127.0.0.1:" + server.port())), credentials);
    }

    private ModelConnection ollama(String model) {
        return new ModelConnection(
                "local",
                "本机",
                ModelConnection.PROVIDER_OLLAMA,
                "http://127.0.0.1:" + server.port(),
                model,
                "",
                "",
                8192,
                1024,
                0.1,
                120,
                List.of(),
                true,
                null,
                "",
                ModelConnection.ORIGIN_STORED);
    }

    private ModelConnection openAi(String id, String credentialRef, String apiKeyEnv) {
        return new ModelConnection(
                id,
                "连接 " + id,
                ModelConnection.PROVIDER_OPENAI,
                "http://127.0.0.1:" + server.port(),
                "gpt-x",
                apiKeyEnv,
                credentialRef,
                8192,
                1024,
                0.2,
                120,
                List.of(),
                true,
                null,
                "",
                ModelConnection.ORIGIN_STORED);
    }

    private static final class FakeServer implements AutoCloseable {

        private final HttpServer server;
        private volatile String tags = """
                {"models":[]}
                """;
        private volatile String show = """
                {"capabilities":[]}
                """;
        private volatile int modelsStatus = 200;
        private volatile String models = """
                {"data":[]}
                """;
        private volatile int chatStatus = 200;
        private volatile int chatCalls;
        private volatile java.util.concurrent.atomic.AtomicInteger tagsReads =
                new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");

        private FakeServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                lastAuthorization.set(authorization);
            }
            if ("/api/tags".equals(path)) {
                tagsReads.incrementAndGet();
                respond(exchange, 200, tags);
                return;
            }
            if ("/api/show".equals(path)) {
                respond(exchange, 200, show);
                return;
            }
            if ("/models".equals(path)) {
                respond(exchange, modelsStatus, models);
                return;
            }
            if ("/chat/completions".equals(path)) {
                chatCalls++;
                respond(exchange, chatStatus, "{\"choices\":[]}");
                return;
            }
            respond(exchange, 404, "");
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
