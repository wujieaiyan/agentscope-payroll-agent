package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.model.AgentModelSettings;
import io.agentscope.examples.payroll.model.ModelCatalog;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelConnections;
import io.agentscope.examples.payroll.model.ModelDefaults;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelConnectionRegistryTest {

    private FakeNacos nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("未配置默认连接时控制面回退 PAYROLL_MODEL 环境连接")
    void controlFallsBackToEnvConnection() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);

        ModelConnection control = registry.resolveControl(AgentModelSettings.none());

        assertEquals(ModelConnections.ENV_CONTROL_ID, control.id());
        assertEquals(ModelConnection.ORIGIN_ENV, control.origin());
        assertEquals(
                1,
                registry.connections().stream()
                        .filter(item -> item.id().equals(ModelConnections.ENV_CONTROL_ID))
                        .count());
    }

    @Test
    @DisplayName("页面控制面默认连接优先于环境连接")
    void storedControlDefaultWinsOverEnv() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        registry.saveDefaults(new ModelDefaults("dash", "", ""), catalog.revision(), "admin");

        assertEquals("dash", registry.resolveControl(AgentModelSettings.none()).id());
    }

    @Test
    @DisplayName("智能体绑定连接优先于页面默认，并按字段覆盖")
    void agentBindingWinsAndOverlays() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        catalog =
                registry.saveConnection(
                        connection("deep", "deepseek-chat", 4096, List.of("stream"), true),
                        catalog.revision(),
                        "admin");
        registry.saveDefaults(new ModelDefaults("dash", "", ""), catalog.revision(), "admin");

        ModelConnection bound =
                registry.resolveControl(
                        new AgentModelSettings("deep", "deepseek-chat", 16384, 2048, 0.7, 60));

        assertEquals("deep", bound.id());
        assertEquals("deepseek-chat", bound.model());
        assertEquals(16384, bound.contextWindow());
        assertEquals(2048, bound.maxTokens());
        assertEquals(0.7, bound.temperature());
        assertEquals(60, bound.timeoutSeconds());
    }

    @Test
    @DisplayName("只给模型名时沿用默认连接的其余参数")
    void agentOverrideWithoutConnectionOverlaysDefault() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        registry.saveDefaults(new ModelDefaults("dash", "", ""), catalog.revision(), "admin");

        ModelConnection overridden =
                registry.resolveControl(new AgentModelSettings("", "qwen-plus", null, null, null, null));

        assertEquals("dash", overridden.id());
        assertEquals("qwen-plus", overridden.model());
        assertEquals(8192, overridden.contextWindow());
        assertEquals(1024, overridden.maxTokens());
    }

    @Test
    @DisplayName("智能体绑定的连接不存在时明确失败，不回落默认")
    void missingAgentConnectionFails() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.resolveControl(
                                        new AgentModelSettings("ghost", "", null, null, null, null)));

        assertEquals(ModelConfigException.CONNECTION_UNAVAILABLE, error.code());
        assertTrue(error.getMessage().contains("ghost"), error.getMessage());
    }

    @Test
    @DisplayName("停用仍是默认值的连接被拒绝，默认连接保持可用")
    void disablingDefaultRejected() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        catalog = registry.saveDefaults(new ModelDefaults("dash", "", "dash"), catalog.revision(), "admin");

        long revision = catalog.revision();
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveConnection(
                                        connection("dash", "qwen-max", 8192, List.of("stream"), false),
                                        revision,
                                        "admin"));
        assertEquals(ModelConfigException.INVALID_CONNECTION, error.code());
        assertTrue(error.getMessage().contains("默认"), error.getMessage());

        assertEquals("dash", registry.resolveControl(AgentModelSettings.none()).id());
        assertTrue(registry.find("dash").orElseThrow().enabled());
        assertEquals(revision, registry.revision());
    }

    @Test
    @DisplayName("停用非默认连接不受限制")
    void disablingNonDefaultAllowed() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        catalog =
                registry.saveConnection(
                        connection("spare", "qwen-max", 8192, List.of("stream"), true),
                        catalog.revision(),
                        "admin");
        catalog = registry.saveDefaults(new ModelDefaults("dash", "", ""), catalog.revision(), "admin");

        registry.saveConnection(
                connection("spare", "qwen-max", 8192, List.of("stream"), false),
                catalog.revision(),
                "admin");

        assertFalse(registry.find("spare").orElseThrow().enabled());
        assertEquals("dash", registry.resolveControl(AgentModelSettings.none()).id());
    }

    @Test
    @DisplayName("轻量面默认优先，缺失时回退控制面")
    void utilityFallsBackToControl() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        assertEquals(ModelConnections.ENV_CONTROL_ID, registry.resolveUtility().id());

        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        registry.saveDefaults(new ModelDefaults("", "", "dash"), catalog.revision(), "admin");

        assertEquals("dash", registry.resolveUtility().id());
    }

    @Test
    @DisplayName("写入必须携带当前 revision，过期提交被拒绝")
    void staleRevisionRejected() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        registry.saveConnection(
                connection("dash", "qwen-max", 8192, List.of("stream"), true), 0, "admin");

        ModelConfigException saveError =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveConnection(
                                        connection("other", "m", 8192, List.of(), true),
                                        0,
                                        "admin"));
        assertEquals(ModelConfigException.REVISION_CONFLICT, saveError.code());

        ModelConfigException defaultsError =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveDefaults(
                                        new ModelDefaults("dash", "", ""), 0, "admin"));
        assertEquals(ModelConfigException.REVISION_CONFLICT, defaultsError.code());
    }

    @Test
    @DisplayName("仍被使用面引用的连接不能删除")
    void deletingDefaultConnectionRejected() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("dash", "qwen-max", 8192, List.of("stream"), true),
                        0,
                        "admin");
        catalog = registry.saveDefaults(new ModelDefaults("dash", "", ""), catalog.revision(), "admin");
        long protectedRevision = catalog.revision();

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> registry.deleteConnection("dash", protectedRevision, "admin"));
        assertEquals(ModelConfigException.INVALID_CONNECTION, error.code());
        assertTrue(error.getMessage().contains("默认"), error.getMessage());

        ModelCatalog after =
                registry.saveDefaults(new ModelDefaults("", "", ""), catalog.revision(), "admin");
        registry.deleteConnection("dash", after.revision(), "admin");
        assertTrue(registry.find("dash").isEmpty());
    }

    @Test
    @DisplayName("环境变量映射的连接 ID 保留，不允许页面写入")
    void reservedEnvIdsRejected() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveConnection(
                                        connection(
                                                ModelConnections.ENV_CONTROL_ID,
                                                "m",
                                                8192,
                                                List.of(),
                                                true),
                                        0,
                                        "admin"));
        assertEquals(ModelConfigException.INVALID_CONNECTION, error.code());
        assertTrue(error.getMessage().contains("保留"), error.getMessage());
    }

    @Test
    @DisplayName("仓储里残留的环境 ID 不重复出现在目录中")
    void storedEnvIdFilteredFromCatalog() throws IOException {
        nacos = new FakeNacos();
        nacos.configs.put(
                ModelConnectionRegistry.DATA_ID,
                """
                {"version":1,"revision":3,"defaults":{},"connections":[
                  {"id":"env-control","name":"旧环境行","provider":"ollama","model":"legacy","enabled":true}
                ]}
                """);

        ModelCatalog catalog = registry(true).catalog();

        assertEquals(
                1,
                catalog.connections().stream()
                        .filter(item -> item.id().equals(ModelConnections.ENV_CONTROL_ID))
                        .count());
        assertFalse(
                catalog.connections().stream()
                        .anyMatch(item -> "旧环境行".equals(item.name())));
    }

    @Test
    @DisplayName("编码面要求 32k 上下文与 tools 能力")
    void codingPlaneRequiresToolsAndContext() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        ModelCatalog catalog =
                registry.saveConnection(
                        connection("small", "m", 8192, List.of("stream"), true), 0, "admin");
        long smallRevision = catalog.revision();

        if (ModelConnections.envCodingConfigured()) {
            ModelConfigException locked =
                    assertThrows(
                            ModelConfigException.class,
                            () ->
                                    registry.saveDefaults(
                                            new ModelDefaults("", "small", ""),
                                            smallRevision,
                                            "admin"));
            assertEquals(ModelConfigException.ENV_LOCKED, locked.code());
            return;
        }

        ModelConfigException lowContext =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveDefaults(
                                        new ModelDefaults("", "small", ""),
                                        smallRevision,
                                        "admin"));
        assertEquals(ModelConfigException.CAPABILITY_UNSUPPORTED, lowContext.code());

        catalog =
                registry.saveConnection(
                        connection("big-no-tools", "m", 65_536, List.of("stream"), true),
                        catalog.revision(),
                        "admin");
        long noToolsRevision = catalog.revision();
        ModelConfigException noTools =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveDefaults(
                                        new ModelDefaults("", "big-no-tools", ""),
                                        noToolsRevision,
                                        "admin"));
        assertEquals(ModelConfigException.CAPABILITY_UNSUPPORTED, noTools.code());

        catalog =
                registry.saveConnection(
                        connection("big", "m", 65_536, List.of("tools", "stream"), true),
                        catalog.revision(),
                        "admin");
        registry.saveDefaults(new ModelDefaults("", "big", ""), catalog.revision(), "admin");

        assertEquals("big", registry.resolveCoding().orElseThrow().id());
    }

    @Test
    @DisplayName("编码面未配置页面默认时返回空（环境变量优先另测）")
    void codingEmptyWhenUnconfigured() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);

        assertEquals(
                ModelConnections.envCodingConfigured(), registry.resolveCoding().isPresent());
    }

    @Test
    @DisplayName("保存默认连接时必须存在且启用")
    void defaultsRequireExistingEnabledConnections() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);

        ModelConfigException missing =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveDefaults(
                                        new ModelDefaults("ghost", "", ""), 0, "admin"));
        assertEquals(ModelConfigException.CONNECTION_UNAVAILABLE, missing.code());
    }

    @Test
    @DisplayName("写路径读取 Nacos 失败时 fail-closed，503 且不写入旧目录")
    void writeFailsClosedWhenNacosReadFails() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(true);
        registry.saveConnection(
                connection("dash", "qwen-max", 8192, List.of("stream"), true), 0, "admin");
        int writesBefore = nacos.writes.get();

        nacos.failReads = true;
        PayrollUnavailableException error =
                assertThrows(
                        PayrollUnavailableException.class,
                        () ->
                                registry.saveConnection(
                                        connection("other", "m", 8192, List.of(), true),
                                        1,
                                        "admin"));
        assertTrue(error.getMessage().contains("payroll-models.json"), error.getMessage());
        assertEquals(writesBefore, nacos.writes.get(), "读取失败时不得写入");
        assertTrue(registry.find("dash").isPresent(), "读路径仍可回落上次目录");

        nacos.failReads = false;
        registry.saveConnection(
                connection("other", "m", 8192, List.of(), true), 1, "admin");
        assertTrue(registry.find("other").isPresent());
    }

    @Test
    @DisplayName("Nacos 未启用时只读，解析走环境连接")
    void disabledNacosIsReadOnly() throws IOException {
        nacos = new FakeNacos();
        ModelConnectionRegistry registry = registry(false);

        assertFalse(registry.writable());
        assertEquals(ModelConnections.ENV_CONTROL_ID, registry.resolveControl(AgentModelSettings.none()).id());
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                registry.saveConnection(
                                        connection("dash", "m", 8192, List.of(), true),
                                        0,
                                        "admin"));
        assertEquals(ModelConfigException.INVALID_CONNECTION, error.code());
        assertTrue(error.getMessage().contains("Nacos 未启用"), error.getMessage());
        assertEquals(0, nacos.writes.get());
    }

    private ModelConnectionRegistry registry(boolean enabled) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        enabled,
                        "http://127.0.0.1:" + nacos.server.getAddress().getPort(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(60));
        return new ModelConnectionRegistry(settings);
    }

    private static ModelConnection connection(
            String id, String model, Integer contextWindow, List<String> capabilities, boolean enabled) {
        return new ModelConnection(
                id,
                "连接 " + id,
                ModelConnection.PROVIDER_OPENAI,
                "https://api." + id + ".test",
                model,
                "",
                "",
                contextWindow,
                1024,
                0.2,
                120,
                capabilities,
                enabled,
                null,
                "seed",
                ModelConnection.ORIGIN_STORED);
    }

    private static final class FakeNacos implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, String> configs = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger writes =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean failReads;

        private FakeNacos() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/nacos/v1/cs/configs", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (failReads) {
                    respond(exchange, 500, "injected read failure");
                    return;
                }
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
            if ("DELETE".equals(exchange.getRequestMethod())) {
                configs.remove(query(exchange).get("dataId"));
                respond(exchange, 200, "true");
                return;
            }
            Map<String, String> form = form(exchange);
            configs.put(form.get("dataId"), form.get("content"));
            respond(exchange, 200, "true");
        }

        private static Map<String, String> query(HttpExchange exchange) {
            return parse(exchange.getRequestURI().getRawQuery());
        }

        private static Map<String, String> form(HttpExchange exchange) throws IOException {
            return parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }

        private static Map<String, String> parse(String raw) {
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
}
