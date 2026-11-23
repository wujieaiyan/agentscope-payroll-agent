package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import io.agentscope.examples.payroll.config.PayrollCoreConfiguration;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.router.AgentRouter;
import io.agentscope.examples.payroll.router.PayrollRouteAdvisor;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import io.agentscope.payroll.runtime.InternalTurnController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InternalTurnControllerTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "test-token";

    private PayrollDatabase database;
    private H2ChatStore chatStore;
    private H2ChatTurnStore turnStore;
    private TurnLeaseService leases;
    private InMemoryTurnEventBus bus;
    private SimpleMeterRegistry registry;
    private CountDownLatch release;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("internal-turn-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        turnStore = new H2ChatTurnStore(database);
        leases = TurnLeaseService.disabled();
        bus = new InMemoryTurnEventBus();
        registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
        release = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (executor != null) {
            executor.shutdownNow();
        }
        TurnMetrics.useRegistry(null);
    }

    @Test
    @DisplayName("执行线程饱和但队列未满时返回 202 并写入排队位置事件")
    void emitsQueuedEventWhenThreadSaturated() throws Exception {
        executor = occupy(new PayrollCoreConfiguration().chatStreamExecutor(1, 2));
        long sessionId = chatStore.create(USER_ID, "排队会话").id();
        ChatSessionManager web = manager("web-1");
        ChatSessionManager.TurnContext turn = web.beginTurn(USER_ID, sessionId, "帮我算工资");
        InternalTurnController controller = controller(manager("runtime-1"));

        ResponseEntity<Map<String, Object>> response = submit(controller, turn, "帮我算工资");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        Map<String, Object> event =
                bus.read(turn.turnId(), 0L, 20).stream()
                        .filter(item -> "queued".equals(item.event()))
                        .findFirst()
                        .map(item -> JsonSupport.fromJson(item.data(), Map.class))
                        .orElseThrow();
        assertEquals(1, ((Number) event.get("position")).intValue());
        assertTrue(((Number) event.get("etaSeconds")).intValue() >= 5);
    }

    @Test
    @DisplayName("线程与队列占满时返回统一 429 契约并标记轮次失败")
    void rejectsWithBusyContractWhenSaturated() throws Exception {
        executor = occupy(new PayrollCoreConfiguration().chatStreamExecutor(1, 1));
        ChatSessionManager web = manager("web-1");
        ChatSessionManager runtime = manager("runtime-1");
        InternalTurnController controller = controller(runtime);

        long first = chatStore.create(USER_ID, "排队会话").id();
        ChatSessionManager.TurnContext firstTurn = web.beginTurn(USER_ID, first, "第一条");
        assertEquals(
                HttpStatus.ACCEPTED, submit(controller, firstTurn, "第一条").getStatusCode());

        long second = chatStore.create(USER_ID, "拒绝会话").id();
        ChatSessionManager.TurnContext secondTurn = web.beginTurn(USER_ID, second, "第二条");
        ResponseEntity<Map<String, Object>> response = submit(controller, secondTurn, "第二条");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(false, response.getBody().get("accepted"));
        assertEquals("BUSY", response.getBody().get("code"));
        assertEquals(5, response.getBody().get("retryAfterSeconds"));
        assertEquals("当前排队已满，请稍后重试", response.getBody().get("message"));
        assertEquals(
                ChatTurn.FAILED, runtime.turn(USER_ID, second, secondTurn.turnId()).status());
        assertEquals(
                1.0,
                registry.find(TurnMetrics.STREAM_REJECTED)
                        .tag("reason", "runtime")
                        .counter()
                        .count());
    }

    @Test
    @DisplayName("签名不合法时拒绝内部提交")
    void rejectsInvalidSignature() throws Exception {
        executor = occupy(new PayrollCoreConfiguration().chatStreamExecutor(1, 2));
        long sessionId = chatStore.create(USER_ID, "鉴权会话").id();
        ChatSessionManager web = manager("web-1");
        ChatSessionManager.TurnContext turn = web.beginTurn(USER_ID, sessionId, "帮我算工资");
        InternalTurnController controller = controller(manager("runtime-1"));

        ResponseEntity<Map<String, Object>> response =
                controller.submit(
                        String.valueOf(Instant.now().getEpochSecond()),
                        "bad-signature",
                        body(turn, "帮我算工资"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody().get("code"));
    }

    private ExecutorService occupy(ExecutorService pool) throws InterruptedException {
        pool.execute(
                () -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        long deadline = System.currentTimeMillis() + 2_000L;
        while (((ThreadPoolExecutor) pool).getActiveCount() == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(1, ((ThreadPoolExecutor) pool).getActiveCount());
        return pool;
    }

    private ResponseEntity<Map<String, Object>> submit(
            InternalTurnController controller,
            ChatSessionManager.TurnContext turn,
            String message) {
        String payload = body(turn, message);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(TOKEN, "POST", "/internal/turns", timestamp, payload);
        return controller.submit(timestamp, signature, payload);
    }

    private String body(ChatSessionManager.TurnContext turn, String message) {
        return "{"
                + "\"turn_id\":\""
                + turn.turnId()
                + "\","
                + "\"session_id\":"
                + turn.sessionId()
                + ","
                + "\"user_id\":"
                + USER_ID
                + ","
                + "\"message\":\""
                + message
                + "\","
                + "\"fencing\":"
                + turn.fencingToken()
                + ","
                + "\"owner_node\":\"web-1\""
                + "}";
    }

    private InternalTurnController controller(ChatSessionManager runtime) {
        return new InternalTurnController(
                runtime,
                executor,
                AgentRouter.disabled(),
                null,
                null,
                ActiveTeamService.disabled(),
                SemanticRouteClassifier.disabled("测试未配置"),
                PayrollRouteAdvisor.disabled(),
                false,
                true,
                false,
                true,
                20,
                TOKEN,
                "all",
                120L);
    }

    private ChatSessionManager manager(String node) {
        PayrollChatFactory factory =
                (session, user) ->
                        new PayrollChat() {
                            @Override
                            public Reply chat(String message) {
                                return new Reply("ok", List.of(), "stub");
                            }
                        };
        return new ChatSessionManager(
                chatStore,
                factory,
                VectorMemoryService.disabled(),
                StaticRunRecorder.disabled(),
                ShadowTeamService.disabled(),
                ActiveTeamService.disabled(),
                10,
                60,
                null,
                turnStore,
                leases,
                bus,
                node,
                10);
    }
}
