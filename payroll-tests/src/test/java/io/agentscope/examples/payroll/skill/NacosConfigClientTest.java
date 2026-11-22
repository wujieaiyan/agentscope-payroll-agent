package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
    @DisplayName("按 dataId 模式列出配置并解析正文与更新时间")
    void listsConfigsByPattern() throws IOException {
        Map<String, String> lastQuery = new HashMap<>();
        start(
                exchange -> {
                    lastQuery.putAll(query(exchange));
                    respond(
                            exchange,
                            200,
                            "{\"totalCount\":1,\"pageItems\":[{\"dataId\":\"payroll-calculator.md\","
                                    + "\"group\":\"payroll-agent\",\"content\":\"## 角色\","
                                    + "\"gmtModified\":1760000000000}]}");
                });

        List<NacosConfigClient.ConfigEntry> entries =
                client(settings("", "")).list("*.md");

        assertEquals(1, entries.size());
        assertEquals("payroll-calculator.md", entries.get(0).dataId());
        assertEquals("## 角色", entries.get(0).content());
        assertEquals(1_760_000_000_000L, entries.get(0).updatedAt());
        assertEquals("blur", lastQuery.get("search"));
        assertEquals("*.md", lastQuery.get("dataId"));
        assertEquals("payroll-agent", lastQuery.get("group"));
    }

    @Test
    @DisplayName("服务端错误返回空并降级")
    void serverErrorReturnsEmpty() throws IOException {
        start(exchange -> respond(exchange, 500, "boom"));

        assertEquals(Optional.empty(), client(settings("", "")).fetch("payroll-calculator.md"));
    }

    @Test
    @DisplayName("读取结果区分不存在、空正文与失败")
    void fetchResultReportsStatus() throws IOException {
        start(
                exchange -> {
                    String dataId = query(exchange).get("dataId");
                    switch (dataId) {
                        case "absent.md" -> respond(exchange, 404, "config data not exist");
                        case "blank.md" -> respond(exchange, 200, "   ");
                        case "error.md" -> respond(exchange, 500, "boom");
                        default -> respond(exchange, 200, "## 角色");
                    }
                });

        NacosConfigClient client = client(settings("", ""));

        assertEquals(
                NacosConfigClient.FetchResult.Status.ABSENT,
                client.fetchResult("absent.md").status());
        assertEquals(
                NacosConfigClient.FetchResult.Status.ABSENT,
                client.fetchResult("blank.md").status());
        NacosConfigClient.FetchResult failed = client.fetchResult("error.md");
        assertEquals(NacosConfigClient.FetchResult.Status.ERROR, failed.status());
        assertTrue(failed.message().contains("500"), failed.message());
        assertEquals("## 角色", client.fetchResult("ok.md").content().orElseThrow());
    }

    @Test
    @DisplayName("连接失败与中断都表达为 ERROR")
    void fetchResultReportsIoFailure() throws IOException {
        start(exchange -> respond(exchange, 200, "ok"));
        NacosConfigClient client = client(settings("", ""));
        server.stop(0);
        server = null;

        NacosConfigClient.FetchResult failed = client.fetchResult("payroll-calculator.md");

        assertEquals(NacosConfigClient.FetchResult.Status.ERROR, failed.status());
    }

    @Test
    @DisplayName("403 后清空 token，下次读取重新登录")
    void forbiddenClearsToken() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger configCalls = new AtomicInteger();
        start(
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/auth/login")) {
                        logins.incrementAndGet();
                        respond(exchange, 200, "{\"accessToken\":\"tk\",\"tokenTtl\":1800}");
                        return;
                    }
                    Map<String, String> query = query(exchange);
                    if (configCalls.incrementAndGet() == 1) {
                        respond(exchange, 403, "no permission");
                        return;
                    }
                    if ("tk".equals(query.get("accessToken"))) {
                        respond(exchange, 200, "remote");
                    } else {
                        respond(exchange, 403, "no permission");
                    }
                });
        NacosConfigClient client = client(settings("", "nacos"));

        NacosConfigClient.FetchResult denied = client.fetchResult("payroll-calculator.md");
        assertEquals(NacosConfigClient.FetchResult.Status.ERROR, denied.status());

        assertEquals(Optional.of("remote"), client.fetch("payroll-calculator.md"));
        assertEquals(2, logins.get(), "403 后应重新登录获取 token");
    }

    @Test
    @DisplayName("列表结果区分空列表、格式错误与服务端错误")
    void listResultReportsStatus() throws IOException {
        start(
                exchange -> {
                    String dataId = query(exchange).get("dataId");
                    if ("*.bad".equals(dataId)) {
                        respond(exchange, 200, "{\"pageItems\":\"oops\"}");
                        return;
                    }
                    if ("*.down".equals(dataId)) {
                        respond(exchange, 500, "boom");
                        return;
                    }
                    respond(exchange, 200, "{\"totalCount\":0,\"pageItems\":[]}");
                });
        NacosConfigClient client = client(settings("", ""));

        assertTrue(client.listResult("*.md").ok());
        assertTrue(client.listResult("*.md").entries().isEmpty());
        assertFalse(client.listResult("*.bad").ok());
        assertFalse(client.listResult("*.down").ok());
    }

    @Test
    @DisplayName("列表接口 404 视为空列表")
    void listMissingReturnsEmptyOk() throws IOException {
        start(exchange -> respond(exchange, 404, "config data not exist"));

        NacosConfigClient.ListResult result = client(settings("", "")).listResult("*.md");

        assertTrue(result.ok());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    @DisplayName("列表超过一页时自动翻页并合并全部结果")
    void listsAllPages() throws IOException {
        List<String> pages = new java.util.concurrent.CopyOnWriteArrayList<>();
        start(
                exchange -> {
                    Map<String, String> query = query(exchange);
                    pages.add(query.get("pageNo"));
                    int pageNo = Integer.parseInt(query.get("pageNo"));
                    int count = pageNo < 3 ? 200 : 50;
                    StringBuilder items = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        if (items.length() > 0) {
                            items.append(',');
                        }
                        items.append("{\"dataId\":\"skill-")
                                .append(pageNo)
                                .append('-')
                                .append(i)
                                .append(".md\",\"content\":\"x\"}");
                    }
                    respond(exchange, 200, "{\"pageItems\":[" + items + "]}");
                });

        NacosConfigClient.ListResult result = client(settings("", "")).listResult("*.md");

        assertTrue(result.ok(), result.warning());
        assertEquals(450, result.entries().size());
        assertEquals(List.of("1", "2", "3"), pages);
    }

    @Test
    @DisplayName("首页整页时继续请求下一页，空页即结束")
    void fullFirstPageRequestsNextPage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        List<String> pages = new java.util.concurrent.CopyOnWriteArrayList<>();
        start(
                exchange -> {
                    requests.incrementAndGet();
                    Map<String, String> query = query(exchange);
                    pages.add(query.get("pageNo"));
                    int pageNo = Integer.parseInt(query.get("pageNo"));
                    if (pageNo > 1) {
                        respond(exchange, 200, "{\"pageItems\":[]}");
                        return;
                    }
                    StringBuilder items = new StringBuilder();
                    for (int i = 0; i < 200; i++) {
                        if (i > 0) {
                            items.append(',');
                        }
                        items.append("{\"dataId\":\"skill-").append(i).append(".md\"}");
                    }
                    respond(exchange, 200, "{\"pageItems\":[" + items + "]}");
                });

        NacosConfigClient.ListResult result = client(settings("", "")).listResult("*.md");

        assertTrue(result.ok(), result.warning());
        assertEquals(200, result.entries().size());
        assertEquals(List.of("1", "2"), pages);
        assertEquals(2, requests.get());
    }

    @Test
    @DisplayName("任一页失败整体失败且不返回部分结果")
    void pageFailureDiscardsPartialResults() throws IOException {
        start(
                exchange -> {
                    int pageNo = Integer.parseInt(query(exchange).get("pageNo"));
                    if (pageNo == 2) {
                        respond(exchange, 500, "boom");
                        return;
                    }
                    StringBuilder items = new StringBuilder();
                    for (int i = 0; i < 200; i++) {
                        if (i > 0) {
                            items.append(',');
                        }
                        items.append("{\"dataId\":\"skill-").append(i).append(".md\"}");
                    }
                    respond(exchange, 200, "{\"pageItems\":[" + items + "]}");
                });

        NacosConfigClient.ListResult result = client(settings("", "")).listResult("*.md");

        assertFalse(result.ok());
        assertTrue(result.entries().isEmpty(), "失败时不返回部分结果");
        assertTrue(result.warning().contains("500"), result.warning());
    }

    @Test
    @DisplayName("超过翻页上限时失败并要求人工清理")
    void stopsAtMaxPages() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        start(
                exchange -> {
                    requests.incrementAndGet();
                    StringBuilder items = new StringBuilder();
                    for (int i = 0; i < 200; i++) {
                        if (i > 0) {
                            items.append(',');
                        }
                        items.append("{\"dataId\":\"skill-").append(i).append(".md\"}");
                    }
                    respond(exchange, 200, "{\"pageItems\":[" + items + "]}");
                });

        NacosConfigClient.ListResult result =
                client(settings("", "", 2)).listResult("*.md");

        assertFalse(result.ok());
        assertTrue(result.entries().isEmpty());
        assertEquals(2, requests.get());
        assertTrue(result.warning().contains("手工清理"), result.warning());
    }

    private NacosConfigClient client(NacosSkillSettings settings) {
        return new NacosConfigClient(settings);
    }

    private NacosSkillSettings settings(String namespace, String username) {
        return settings(namespace, username, NacosSkillSettings.DEFAULT_LIST_MAX_PAGES);
    }

    private NacosSkillSettings settings(String namespace, String username, int listMaxPages) {
        return new NacosSkillSettings(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "payroll-agent",
                namespace,
                username,
                username.isBlank() ? "" : "secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                listMaxPages);
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
