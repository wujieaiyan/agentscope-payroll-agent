package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatTurnLifecycleTest {

    private static final long USER_ID = 7L;

    private PayrollDatabase database;
    private H2ChatStore chatStore;
    private H2ChatTurnStore turnStore;
    private TurnLeaseService sharedLeases;
    private InMemoryTurnEventBus sharedBus;
    private ChatSessionManager manager;
    private long sessionId;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("chat-turn-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        turnStore = new H2ChatTurnStore(database);
        sharedLeases = TurnLeaseService.disabled();
        sharedBus = new InMemoryTurnEventBus();
        sessionId = chatStore.create(USER_ID, "轮次会话").id();
        manager = manager("node-a");
    }

    private ChatSessionManager manager(String node) {
        return new ChatSessionManager(
                chatStore,
                (session, user) ->
                        new PayrollChat() {
                            @Override
                            public Reply chat(String message) {
                                return new Reply("ok", List.of(), "stub");
                            }
                        },
                VectorMemoryService.disabled(),
                StaticRunRecorder.disabled(),
                ShadowTeamService.disabled(),
                ActiveTeamService.disabled(),
                SessionLockService.disabled(),
                10,
                60,
                null,
                turnStore,
                sharedLeases,
                sharedBus,
                node);
    }

    @Test
    @DisplayName("开启轮次写入用户消息与运行中记录，完成后落助手消息并释放占用")
    void beginAndCompleteTurn() {
        ChatSessionManager.TurnContext turn =
                manager.beginTurn(USER_ID, sessionId, "算一下工资");

        ChatTurn saved = manager.turn(USER_ID, sessionId, turn.turnId());
        assertEquals(ChatTurn.RUNNING, saved.status());
        assertEquals("node-a", saved.ownerNode());
        assertEquals(turn.userMessageId(), saved.userMessageId());
        assertEquals(1L, saved.fencingToken());

        PayrollChat.Reply reply =
                new PayrollChat.Reply("已核算", List.of("payroll_calculate"), "stub");
        Long messageId = manager.completeTurn(turn, reply);

        assertNotNull(messageId);
        ChatTurn finished = manager.turn(USER_ID, sessionId, turn.turnId());
        assertEquals(ChatTurn.SUCCEEDED, finished.status());
        assertEquals(messageId, finished.assistantMessageId());

        ChatSessionManager.TurnContext next =
                manager.beginTurn(USER_ID, sessionId, "再算一次");
        assertTrue(next.fencingToken() > saved.fencingToken());
        manager.releaseTurn(next, "测试结束");
    }

    @Test
    @DisplayName("同一会话并发开启轮次被拒绝")
    void concurrentBeginRejected() {
        manager.beginTurn(USER_ID, sessionId, "第一条");
        assertThrows(
                PayrollException.class,
                () -> manager.beginTurn(USER_ID, sessionId, "第二条"));
    }

    @Test
    @DisplayName("事件按序号写入并可增量读取，取消标记可被执行端感知")
    void eventsAndCancel() {
        ChatSessionManager.TurnContext turn =
                manager.beginTurn(USER_ID, sessionId, "第一条");
        long first = manager.emit(turn, "start", Map.of("model", "stub"));
        long second = manager.emit(turn, "text", Map.of("text", "你"));
        long third = manager.emit(turn, "text", Map.of("text", "好"));

        assertEquals(1L, first);
        assertEquals(3L, third);
        List<TurnEventBus.TurnEvent> tail =
                manager.turnEvents(USER_ID, sessionId, turn.turnId(), first, 10);
        assertEquals(2, tail.size());
        assertEquals(second, tail.get(0).seq());

        assertTrue(manager.cancelTurn(USER_ID, sessionId, turn.turnId()));
        assertTrue(manager.turnCancelled(turn));
        assertTrue(manager.cancelTurn(USER_ID, sessionId, turn.turnId()));

        manager.failTurn(turn, "用户取消");
        assertEquals(
                ChatTurn.FAILED,
                manager.turn(USER_ID, sessionId, turn.turnId()).status());
        assertFalse(manager.cancelTurn(USER_ID, sessionId, turn.turnId()));
    }

    @Test
    @DisplayName("超时轮次可被标记过期，且过期后不再接受取消")
    void expireStaleTurn() {
        ChatSessionManager.TurnContext turn =
                manager.beginTurn(USER_ID, sessionId, "第一条");
        assertTrue(manager.expireTurn(turn.turnId(), "节点失联"));
        assertEquals(
                ChatTurn.EXPIRED,
                manager.turn(USER_ID, sessionId, turn.turnId()).status());
        assertFalse(manager.cancelTurn(USER_ID, sessionId, turn.turnId()));
    }

    @Test
    @DisplayName("runtime 节点可接管 Web 已开启的轮次并写入同一事件流")
    void runtimeTakesOverTurn() {
        ChatSessionManager.TurnContext web =
                manager.beginTurn(USER_ID, sessionId, "帮我算工资", "web-1");
        ChatSessionManager runtime = manager("runtime-1");

        ChatSessionManager.TurnContext remote =
                runtime
                        .acceptRemoteTurn(
                                web.turnId(),
                                sessionId,
                                USER_ID,
                                "帮我算工资",
                                web.fencingToken(),
                                "web-1")
                        .orElseThrow();
        assertEquals(web.turnId(), remote.turnId());
        assertTrue(runtime.acceptRemoteTurn(
                        web.turnId(),
                        sessionId,
                        USER_ID,
                        "帮我算工资",
                        web.fencingToken(),
                        "web-1")
                .isEmpty());

        runtime.emit(remote, "start", Map.of("model", "stub"));
        runtime.completeTurn(remote, new PayrollChat.Reply("已核算", List.of(), "stub"));
        runtime.finishRemoteTurn(web.turnId());

        ChatTurn finished = manager.turn(USER_ID, sessionId, web.turnId());
        assertEquals(ChatTurn.SUCCEEDED, finished.status());
        assertEquals(1, sharedBus.read(web.turnId(), 0L, 10).size());
    }
}
