package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    /** 接下来 N 次列表请求返回 500，用于模拟连续失败。 */
    final AtomicInteger failNextList = new AtomicInteger();
    /** 接下来 N 次单条读取返回 500。 */
    final AtomicInteger failNextFetch = new AtomicInteger();
    /** 指定 dataId 的固定响应状态，便于验证 404/403/500 分支。 */
    final Map<String, Integer> statusForDataId = new ConcurrentHashMap<>();
    volatile boolean failWrites;
    volatile boolean failReads;

    private static final ObjectMapper JSON = new ObjectMapper();

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
            Map<String, String> query = query(exchange);
            boolean list = "blur".equals(query.get("search"));
            if (failReads
                    || (list ? consume(failNextList) : consume(failNextFetch))) {
                respond(exchange, 500, "boom");
                return;
            }
            if (list) {
                respond(
                        exchange,
                        200,
                        searchPage(
                                query.get("dataId"),
                                pageNo(query.get("pageNo")),
                                pageSize(query.get("pageSize"))));
                return;
            }
            String dataId = query.get("dataId");
            Integer forced = dataId == null ? null : statusForDataId.get(dataId);
            if (forced != null) {
                respond(
                        exchange,
                        forced,
                        forced == 200 ? configs.getOrDefault(dataId, "") : "forced status");
                return;
            }
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

    private String searchPage(String pattern, int pageNo, int pageSize) throws IOException {
        String suffix = pattern != null && pattern.startsWith("*") ? pattern.substring(1) : null;
        var matched = configs.entrySet().stream()
                .filter(entry -> suffix == null
                        ? entry.getKey().equals(pattern)
                        : entry.getKey().endsWith(suffix))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        int from = Math.min(Math.max(0, (pageNo - 1) * pageSize), matched.size());
        int to = Math.min(from + pageSize, matched.size());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode items = root.putArray("pageItems");
        for (var entry : matched.subList(from, to)) {
            ObjectNode item = items.addObject();
            item.put("dataId", entry.getKey());
            item.put("group", "payroll-agent");
            item.put("content", entry.getValue());
            item.put("gmtModified", 1_760_000_000_000L);
        }
        root.put("totalCount", matched.size());
        return JSON.writeValueAsString(root);
    }

    private static int pageNo(String value) {
        return Math.max(1, parseInt(value, 1));
    }

    private static int pageSize(String value) {
        return Math.max(1, parseInt(value, 200));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean consume(AtomicInteger counter) {
        return counter.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0;
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
