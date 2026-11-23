package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.examples.payroll.db.JdbcAgentDefinitionRepository;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.repository.AgentDefinitionRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M5.1：定义来源切换到数据库后，Nacos 停机不影响目录读写，导入一次性且幂等。 */
class NacosAgentRegistryDbSourceTest {

    private PayrollDatabase database;
    private AgentDefinitionRepository repository;
    private FakeNacos nacos;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("agent-registry-db-" + System.nanoTime());
        repository = new JdbcAgentDefinitionRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (nacos != null) {
            nacos.close();
        }
        database.close();
    }

    @Test
    @DisplayName("Nacos 未启用时 DB 模式仍可读写定义")
    void dbSourceWorksWithoutNacos() {
        NacosAgentRegistry registry = registry(false, "http://127.0.0.1:1");

        assertTrue(registry.writable());
        assertTrue(registry.location().contains("agent_definition"), registry.location());
        assertTrue(registry.list().isEmpty());

        AgentDefinition saved = registry.save(agent(null, "个税顾问"), "admin");
        assertEquals(1, registry.list().size());
        assertEquals(saved.id(), registry.list().get(0).id());

        NacosAgentRegistry reloaded = registry(false, "http://127.0.0.1:1");
        assertEquals(saved.id(), reloaded.list().get(0).id());
        assertTrue(reloaded.delete(saved.id()));
        assertTrue(reloaded.list().isEmpty());
    }

    @Test
    @DisplayName("Nacos 停机时列表与保存仍可用（通知失败只告警）")
    void nacosOutageDoesNotBlockDefinitions() {
        NacosAgentRegistry registry = registry(true, "http://127.0.0.1:1");

        AgentDefinition saved = registry.save(agent(null, "社保顾问"), "admin");
        assertTrue(
                registry.list().stream().anyMatch(item -> item.id().equals(saved.id())),
                registry.list().toString());
        assertEquals(1, repository.load().items().size());
    }

    @Test
    @DisplayName("过期 revision 在 DB 模式下同样返回 409")
    void staleRevisionRejected() {
        NacosAgentRegistry registry = registry(false, "http://127.0.0.1:1");
        String base = registry.revision();
        AgentDefinition saved = registry.save(agent(null, "个税顾问"), "admin", base, true);

        PayrollConflictException error =
                assertThrows(
                        PayrollConflictException.class,
                        () -> registry.save(agent(saved.id(), "覆盖者"), "other", base, true));
        assertEquals(registry.revision(), error.currentRevision());
        assertEquals("个税顾问", repository.find(saved.id()).orElseThrow().name());
    }

    @Test
    @DisplayName("启动导入只执行一次，重复调用不产生重复行")
    void importsFromNacosOnce() throws IOException {
        nacos = new FakeNacos();
        nacos.configs.put(
                NacosAgentRegistry.DATA_ID,
                """
                [
                  {"id":"payroll-calculator","name":"薪酬计算专家","avatar":"🧮",
                   "description":"核算","tags":["薪酬"],"skillIds":["payroll-calculator"],
                   "visibility":"PUBLIC","status":"ONLINE","capabilities":["calc"]},
                  {"id":"payroll-validator","name":"薪酬复核专家","avatar":"🔍",
                   "description":"复核","tags":["薪酬"],"skillIds":["payroll-validator"],
                   "visibility":"PUBLIC","status":"ONLINE","capabilities":["calc"]}
                ]
                """);

        NacosAgentRegistry registry = registry(true, nacos.url());
        assertEquals(2, registry.importFromNacosIfEmpty());
        assertEquals(0, registry.importFromNacosIfEmpty());
        assertEquals(2, registry.list().size());

        registry.save(agent(null, "新增顾问"), "admin");
        assertEquals(3, repository.load().items().size());
    }

    @Test
    @DisplayName("Nacos 模式下导入器不生效")
    void importerInactiveForNacosSource() throws IOException {
        nacos = new FakeNacos();
        NacosAgentRegistry registry =
                new NacosAgentRegistry(
                        settings(true, nacos.url()),
                        new NacosRuntimeSettings(settings(true, nacos.url())),
                        repository,
                        NacosAgentRegistry.SOURCE_NACOS);

        assertEquals(0, registry.importFromNacosIfEmpty());
        assertTrue(repository.load().items().isEmpty());
    }

    private NacosAgentRegistry registry(boolean nacosEnabled, String server) {
        NacosSkillSettings settings = settings(nacosEnabled, server);
        return new NacosAgentRegistry(
                settings,
                new NacosRuntimeSettings(settings),
                repository,
                NacosAgentRegistry.SOURCE_DB);
    }

    private static NacosSkillSettings settings(boolean enabled, String server) {
        return new NacosSkillSettings(
                enabled,
                server,
                "payroll-agent",
                "",
                "",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(60));
    }

    private static AgentDefinition agent(String id, String name) {
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

        private FakeNacos() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/nacos/v1/cs/configs", this::handle);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                String dataId = query(exchange).get("dataId");
                String body = dataId == null ? null : configs.get(dataId);
                if (body == null) {
                    respond(exchange, 404, "config data not exist");
                } else {
                    respond(exchange, 200, body);
                }
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
