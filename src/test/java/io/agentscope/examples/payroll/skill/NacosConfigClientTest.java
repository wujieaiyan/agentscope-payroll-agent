package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosConfigClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("按 dataId/group 读取配置正文")
    void fetchesBodyWithQuery() throws IOException {
        Map<String, String> lastQuery = new HashMap<>();
        start(
                exchange -> {
                    lastQuery.putAll(query(exchange));
                    respond(exchange, 200, "## 角色\n来自 Nacos。");
                });

        Optional<String> body = client(settings("", "")).fetch("payroll-calculator.md");

        assertEquals(Optional.of("## 角色\n来自 Nacos。"), body);
        assertEquals("payroll-calculator.md", lastQuery.get("dataId"));
        assertEquals("payroll-agent", lastQuery.get("group"));
        assertTrue(!lastQuery.containsKey("tenant"));
    }

    @Test
    @DisplayName("配置不存在返回空而不是抛异常")
    void missingConfigReturnsEmpty() throws IOException {
        start(exchange -> respond(exchange, 404, "config data not exist"));

        assertEquals(Optional.empty(), client(settings("", "")).fetch("missing.md"));
    }

    @Test
    @DisplayName("命名空间与登录 token 会附加到请求参数")
    void namespaceAndToken() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        start(
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/auth/login")) {
                        logins.incrementAndGet();
                        respond(exchange, 200, "{\"accessToken\":\"tk\",\"tokenTtl\":1800}");
                        return;
                    }
                    Map<String, String> query = query(exchange);
                    if ("ns-dev".equals(query.get("tenant")) && "tk".equals(query.get("accessToken"))) {
                        respond(exchange, 200, "remote");
                    } else {
                        respond(exchange, 403, "no permission");
                    }
                });

        NacosConfigClient client = client(settings("ns-dev", "nacos"));

        assertEquals(Optional.of("remote"), client.fetch("payroll-calculator.md"));
        assertEquals(Optional.of("remote"), client.fetch("payroll-validator.md"));
        assertEquals(1, logins.get(), "登录 token 应复用");
    }

    @Test
    @DisplayName("服务端错误返回空并降级")
    void serverErrorReturnsEmpty() throws IOException {
        start(exchange -> respond(exchange, 500, "boom"));

        assertEquals(Optional.empty(), client(settings("", "")).fetch("payroll-calculator.md"));
    }

    private NacosConfigClient client(NacosSkillSettings settings) {
        return new NacosConfigClient(settings);
    }

    private NacosSkillSettings settings(String namespace, String username) {
        return new NacosSkillSettings(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "payroll-agent",
                namespace,
                username,
                username.isBlank() ? "" : "secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(60));
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nacos/v1/cs/configs", handler);
        server.createContext("/nacos/v1/auth/login", handler);
        server.start();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                values.put(
                        URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
