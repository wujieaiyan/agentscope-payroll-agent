package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosAgentRegistryTest {

    private FakeNacos nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("Nacos 缺少配置时回退三个内置智能体")
    void fallsBackToBuiltinsWhenConfigMissing() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(true);

        List<AgentDefinition> agents = registry.list();

        assertEquals(RoleSkills.ids(), agents.stream().map(AgentDefinition::id).toList());
        assertTrue(registry.writable());
        assertTrue(registry.location().contains("payroll-agent"), registry.location());
    }

    @Test
    @DisplayName("保存新智能体写入 Nacos，新实例可重新读取")
    void savesNewAgentAndReloads() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(true);

        AgentDefinition saved = registry.save(newAgent(null, "个税顾问"), "admin");
        assertNotNull(saved.id());
        assertTrue(saved.id().startsWith("agent-"), saved.id());
        assertEquals("admin", saved.createdBy());
        assertTrue(nacos.configs.containsKey(NacosAgentRegistry.DATA_ID));

        List<AgentDefinition> reloaded = registry(true).list();
        assertTrue(
                reloaded.stream().anyMatch(agent -> agent.id().equals(saved.id())),
                reloaded.toString());
    }

    @Test
    @DisplayName("更新保留创建人与创建时间，只刷新内容与更新时间")
    void updatePreservesCreatedMetadata() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(true);
        AgentDefinition saved = registry.save(newAgent(null, "个税顾问"), "admin");

        AgentDefinition updated =
                registry.save(
                        new AgentDefinition(
                                saved.id(),
                                "个税专家",
                                "🧾",
                                "只处理个税",
                                List.of("税务"),
                                List.of(RoleSkills.VALIDATOR),
                                AgentDefinition.VISIBILITY_PRIVATE,
                                AgentDefinition.STATUS_OFFLINE,
                                "ignored",
                                null,
                                null),
                        "other");

        assertEquals(saved.id(), updated.id());
        assertEquals("个税专家", updated.name());
        assertEquals("admin", updated.createdBy());
        assertEquals(saved.createdAt(), updated.createdAt());
        assertFalse(updated.updatedAt().isBefore(saved.createdAt()));

        AgentDefinition reloaded =
                registry(true).list().stream()
                        .filter(agent -> agent.id().equals(saved.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("个税专家", reloaded.name());
        assertEquals(AgentDefinition.STATUS_OFFLINE, reloaded.status());
    }

    @Test
    @DisplayName("删除智能体同步写入 Nacos")
    void deletePersists() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(true);
        AgentDefinition saved = registry.save(newAgent(null, "个税顾问"), "admin");

        assertTrue(registry.delete(saved.id()));
        assertFalse(registry.delete(saved.id()));
        assertTrue(
                registry(true).list().stream().noneMatch(agent -> agent.id().equals(saved.id())));
    }

    @Test
    @DisplayName("Nacos 配置损坏时回退内置智能体")
    void malformedConfigFallsBack() throws IOException {
        nacos = new FakeNacos();
        nacos.configs.put(NacosAgentRegistry.DATA_ID, "{not-json");

        assertEquals(RoleSkills.ids().size(), registry(true).list().size());
    }

    @Test
    @DisplayName("TTL 内复用缓存，不重复读取 Nacos")
    void cachesWithinTtl() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(true);

        registry.list();
        registry.list();
        assertEquals(1, nacos.reads.get());

        registry.save(newAgent(null, "个税顾问"), "admin");
        assertEquals(1, nacos.reads.get(), "保存应复用缓存列表");
        assertEquals(1, nacos.writes.get());
    }

    @Test
    @DisplayName("未启用 Nacos 时只读并回退内置智能体")
    void disabledIsReadOnly() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry = registry(false);

        assertFalse(registry.writable());
        assertEquals(RoleSkills.ids().size(), registry.list().size());
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> registry.save(newAgent(null, "个税顾问"), "admin"));
        assertTrue(error.getMessage().contains("Nacos 未启用"), error.getMessage());
        assertEquals(0, nacos.writes.get());
    }

    @Test
    @DisplayName("Nacos 写入失败时抛出明确异常且不污染缓存")
    void writeFailureThrows() throws IOException {
        nacos = new FakeNacos();
        nacos.failWrites = true;
        NacosAgentRegistry registry = registry(true);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> registry.save(newAgent(null, "个税顾问"), "admin"));
        assertTrue(error.getMessage().contains("写入 Nacos 配置"), error.getMessage());
        assertTrue(nacos.configs.isEmpty());
    }

    private NacosAgentRegistry registry(boolean enabled) {
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
        return new NacosAgentRegistry(settings);
    }

    private static AgentDefinition newAgent(String id, String name) {
        return new AgentDefinition(
                id,
                name,
                "🧾",
                "负责个税测算",
                List.of("税务"),
                List.of(RoleSkills.CALCULATOR),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                null,
                null,
                null);
    }

    private static final class FakeNacos implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, String> configs = new ConcurrentHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private volatile boolean failWrites;

        private FakeNacos() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/nacos/v1/cs/configs", this::handle);
            server.start();
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
            Map<String, String> values = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
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

        private static Map<String, String> form(HttpExchange exchange) throws IOException {
            String raw =
                    new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> values = new HashMap<>();
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
