package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    @DisplayName("runtime 的 429 携带 Retry-After 时透传到异常")
    void parsesRetryAfterHeader() {
        withRuntime(429, Map.of("Retry-After", "12"), client -> {
            TooManyRequestsException error =
                    assertThrows(
                            TooManyRequestsException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertEquals(TooManyRequestsException.CODE_BUSY, error.code());
            assertEquals(12, error.retryAfterSeconds());
        });
    }

    @Test
    @DisplayName("runtime 的 429 缺少或非法 Retry-After 时按 5 秒回退")
    void fallsBackWhenRetryAfterMissing() {
        withRuntime(429, client -> {
            TooManyRequestsException error =
                    assertThrows(
                            TooManyRequestsException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertEquals(5, error.retryAfterSeconds());
        });
        withRuntime(429, Map.of("Retry-After", "abc"), client -> {
            TooManyRequestsException error =
                    assertThrows(
                            TooManyRequestsException.class,
                            () -> client.submit(TURN, "算一下 11 月工资"));
            assertEquals(5, error.retryAfterSeconds());
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

    @Test
    @DisplayName("剩余预算不足 250ms 时不再发起新提交，按 503 返回并累计指标")
    void failsWhenBudgetExhausted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
        try {
            withSlowRuntime(
                    1_000,
                    base -> {
                        AgentRuntimeClient client =
                                new AgentRuntimeClient(
                                        List.of(base, base),
                                        "test-token",
                                        Duration.ofMillis(300),
                                        Duration.ofMillis(500));
                        PayrollUnavailableException error =
                                assertThrows(
                                        PayrollUnavailableException.class,
                                        () -> client.submit(TURN, "算一下 11 月工资"));
                        assertTrue(error.getMessage().contains("预算"), error.getMessage());
                        assertEquals(
                                1.0,
                                registry.find(TurnMetrics.SUBMIT_BUDGET_EXHAUSTED)
                                        .counter()
                                        .count());
                    });
        } finally {
            TurnMetrics.useRegistry(null);
        }
    }

    @Test
    @DisplayName("单地址超时按配置生效，不被总预算放大")
    void appliesPerUrlTimeout() {
        withSlowRuntime(
                1_000,
                base -> {
                    AgentRuntimeClient client =
                            new AgentRuntimeClient(
                                    List.of(base),
                                    "test-token",
                                    Duration.ofMillis(200),
                                    Duration.ofSeconds(10));
                    IllegalStateException error =
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> client.submit(TURN, "算一下 11 月工资"));
                    assertTrue(error.getMessage().contains("200ms"), error.getMessage());
                });
    }

    @Test
    @DisplayName("未配置 runtime 地址时提交直接返回不可用")
    void rejectsEmptyUrls() {
        AgentRuntimeClient client = new AgentRuntimeClient(List.of(), "test-token");

        assertThrows(
                PayrollUnavailableException.class, () -> client.submit(TURN, "算一下 11 月工资"));
    }

    @Test
    @DisplayName("读取 health 的 epoch_millis 作为服务器时间")
    void readsServerTimeFromHealth() {
        withHealthServer(
                200,
                "{\"status\":\"UP\",\"epoch_millis\":1700000000123}",
                client ->
                        assertEquals(
                                java.util.Optional.of(1_700_000_000_123L),
                                client.serverTimeMillis()));
    }

    @Test
    @DisplayName("health 非 2xx 时返回空，不抛异常")
    void returnsEmptyWhenHealthNotOk() {
        withHealthServer(
                503,
                "{\"status\":\"DOWN\"}",
                client -> assertEquals(java.util.Optional.empty(), client.serverTimeMillis()));
    }

    @Test
    @DisplayName("health 缺少 epoch_millis 时返回空")
    void returnsEmptyWithoutEpochMillis() {
        withHealthServer(
                200,
                "{\"status\":\"UP\"}",
                client -> assertEquals(java.util.Optional.empty(), client.serverTimeMillis()));
    }

    @Test
    @DisplayName("未配置 runtime 时不尝试读取服务器时间")
    void noServerTimeWhenUnconfigured() {
        AgentRuntimeClient client = new AgentRuntimeClient(List.of(), "test-token");

        assertEquals(java.util.Optional.empty(), client.serverTimeMillis());
    }

    private static void withRuntime(int status, ClientAssertion assertion) {
        withRuntime(status, Map.of(), assertion);
    }

    private static void withRuntime(
            int status, Map<String, String> headers, ClientAssertion assertion) {
        HttpServer server = null;
        try {
            server = startServer(status, headers);
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

    private static void withSlowRuntime(long delayMillis, StringAssertion assertion) {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/internal/turns",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(202, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            assertion.run("http://127.0.0.1:" + server.getAddress().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("无法启动测试 runtime", e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private static void withHealthServer(int status, String payload, ClientAssertion assertion) {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/internal/health",
                    exchange -> {
                        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
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

    private static HttpServer startServer(int status, Map<String, String> headers)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/turns",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
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

    @FunctionalInterface
    private interface StringAssertion {
        void run(String baseUrl);
    }
}
