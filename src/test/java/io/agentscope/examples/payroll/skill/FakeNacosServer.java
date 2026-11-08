package io.agentscope.examples.payroll.skill;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用 Nacos 配置服务：只实现 v1 configs 的读取、发布与删除。 */
final class FakeNacosServer implements AutoCloseable {

    final Map<String, String> configs = new ConcurrentHashMap<>();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger writes = new AtomicInteger();
    volatile boolean failWrites;

    private final HttpServer server;

    FakeNacosServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nacos/v1/cs/configs", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            reads.incrementAndGet();
            String dataId = query(exchange).get("dataId");
            String body = dataId == null ? null : configs.get(dataId);
            if (body == null) {
                respond(exchange, 404, "config data not exist");
            } else {
                respond(exchange, 200, body);
            }
            return;
        }
        writes.incrementAndGet();
        if (failWrites) {
            respond(exchange, 500, "boom");
            return;
        }
        if ("DELETE".equals(method)) {
            configs.remove(query(exchange).get("dataId"));
            respond(exchange, 200, "true");
            return;
        }
        Map<String, String> form = form(exchange);
        configs.put(form.get("dataId"), form.get("content"));
        respond(exchange, 200, "true");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        return decode(exchange.getRequestURI().getRawQuery());
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        return decode(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                values.put(
                        URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
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

    @Override
    public void close() {
        server.stop(0);
    }
}
