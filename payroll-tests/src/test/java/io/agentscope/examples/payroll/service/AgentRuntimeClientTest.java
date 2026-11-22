package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentRuntimeClientTest {

    private static final ChatSessionManager.TurnContext TURN =
            new ChatSessionManager.TurnContext("turn-1", 7L, 11L, 21L, 1L, "token", "node-a", null);

    @Test
    @DisplayName("runtime 返回 429 时映射为 TooManyRequestsException")
    void mapsTooManyRequests() {
        withRuntime(429, client -> {
            TooManyRequestsException error =
                    assertThrows(
                            TooManyRequestsException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertTrue(error.getMessage().contains("繁忙"), error.getMessage());
        });
    }

    @Test
    @DisplayName("runtime 返回 503 时映射为 PayrollUnavailableException")
    void mapsServiceUnavailable() {
        withRuntime(503, client -> {
            PayrollUnavailableException error =
                    assertThrows(
                            PayrollUnavailableException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertTrue(error.getMessage().contains("不可用"), error.getMessage());
        });
    }

    @Test
    @DisplayName("runtime 返回 409 时提示轮次已被接管")
    void mapsConflict() {
        withRuntime(409, client -> {
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertTrue(error.getMessage().contains("接管"), error.getMessage());
        });
    }

    @Test
    @DisplayName("其它非 2xx 状态码保留 HTTP 状态")
    void reportsUnexpectedStatus() {
        withRuntime(500, client -> {
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
        });
    }

    @Test
    @DisplayName("2xx 视为提交成功")
    void acceptsSuccessStatus() {
        withRuntime(202, client -> assertDoesNotThrow(() -> client.submit(TURN, "算一下 11 月工资")));
    }

    private static void withRuntime(int status, ClientAssertion assertion) {
        HttpServer server = null;
        try {
            server = startServer(status);
            AgentRuntimeClient client =
                    new AgentRuntimeClient(
                            List.of("http://127.0.0.1:" + server.getAddress().getPort()),
                            "test-token");
            assertion.run(client);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动测试 runtime", e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private static HttpServer startServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/turns",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface ClientAssertion {
        void run(AgentRuntimeClient client);
    }
}
