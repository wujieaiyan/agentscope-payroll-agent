package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        return manager(node, 10);
    }

    private ChatSessionManager manager(String node, int heartbeatSeconds) {
        return build(
                (session, user) ->
                        new PayrollChat() {
                            @Override
                            public Reply chat(String message) {
                                return new Reply("ok", List.of(), "stub");
                            }
                        },
                node,
                heartbeatSeconds);
    }

    private ChatSessionManager webManager(String node) {
        return build(null, node, 10);
    }

    private ChatSessionManager build(
            PayrollChatFactory factory, String node, int heartbeatSeconds) {
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
                sharedLeases,
                sharedBus,
                node,
                heartbeatSeconds);
    }

    private void ageHeartbeat(String turnId, Duration age) throws Exception {
        LocalDateTime old = LocalDateTime.now().minus(age);
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_turns SET heartbeat_at = ?, updated_at = ?"
                                        + " WHERE turn_id = ?")) {
            statement.setObject(1, old);
            statement.setObject(2, old);
            statement.setString(3, turnId);
            statement.executeUpdate();
        }
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
    @DisplayName("并发分配围栏令牌互不重复且从 1 递增")
    void concurrentFencingAllocation() throws Exception {
        int threads = 8;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads * perThread; i++) {
                futures.add(pool.submit(() -> turnStore.nextFencing(sessionId)));
            }
            Set<Long> tokens = new HashSet<>();
            for (Future<Long> future : futures) {
                tokens.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(threads * perThread, tokens.size());
            assertEquals(1L, tokens.stream().mapToLong(Long::longValue).min().orElse(0L));
            assertEquals(
                    (long) threads * perThread,
                    tokens.stream().mapToLong(Long::longValue).max().orElse(0L));
        } finally {
            pool.shutdownNow();
        }
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
    @DisplayName("开启轮次时固定绝对截止时间，与连接时长无关")
    void fixesDeadlineAtCreation() {
        ChatSessionManager.TurnContext turn =
                webManager("web-1").beginTurn(USER_ID, sessionId, "第一条");

        ChatTurn saved = manager.turn(USER_ID, sessionId, turn.turnId());
        assertNotNull(saved.deadlineAt());
        assertTrue(saved.deadlineAt().isAfter(LocalDateTime.now().plusMinutes(25)));
        assertTrue(saved.deadlineAt().isBefore(LocalDateTime.now().plusMinutes(35)));
    }

    @Test
    @DisplayName("截止时间已过的运行中轮次由扫描统一标记过期")
    void expiresOverdueRunningTurn() throws Exception {
        ChatSessionManager.TurnContext turn =
                webManager("web-1").beginTurn(USER_ID, sessionId, "第一条");
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_turns SET deadline_at = ? WHERE turn_id = ?")) {
            statement.setObject(1, LocalDateTime.now().minusMinutes(1));
            statement.setString(2, turn.turnId());
            statement.executeUpdate();
        }

        assertEquals(1, turnStore.expireOverdueRunning("轮次超过最长执行时间"));
        assertEquals(
                ChatTurn.EXPIRED,
                manager.turn(USER_ID, sessionId, turn.turnId()).status());
    }

    @Test
    @DisplayName("看门狗同时按绝对截止时间与 last_seq 停滞判定")
    void heartbeatStallReasons() {
        long now = System.currentTimeMillis();
        ChatSessionManager.Heartbeat active =
                new ChatSessionManager.Heartbeat(
                        1L, 1L, "token", LocalDateTime.now().plusMinutes(5));
        assertNull(active.stallReason(now, 60_000L));
        assertTrue(active.stallReason(now + 61_000L, 60_000L).contains("无事件进展"));

        ChatSessionManager.Heartbeat expired =
                new ChatSessionManager.Heartbeat(
                        1L, 1L, "token", LocalDateTime.now().minusSeconds(1));
        assertEquals("轮次超过最长执行时间", expired.stallReason(now, 60_000L));
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

    @Test
    @DisplayName("执行端心跳持续更新，慢回答不会因无结构事件被判失联")
    void heartbeatPreventsFalseExpiry() throws Exception {
        ChatSessionManager executor = manager("node-exec", 1);
        ChatSessionManager.TurnContext turn =
                executor.beginTurn(USER_ID, sessionId, "写一份很长的说明");

        Thread.sleep(2500L);

        assertEquals(0, executor.expireStaleTurns(Duration.ofMillis(1500), "测试扫描"));
        assertEquals(
                ChatTurn.RUNNING,
                executor.turn(USER_ID, sessionId, turn.turnId()).status());
        assertNotNull(executor.turn(USER_ID, sessionId, turn.turnId()).heartbeatAt());

        executor.releaseTurn(turn, "测试结束");
    }

    @Test
    @DisplayName("心跳陈旧且租约缺失的轮次被标记过期")
    void staleTurnExpiresWhenLeaseMissing() throws Exception {
        ChatSessionManager web = webManager("web-1");
        ChatSessionManager.TurnContext turn = web.beginTurn(USER_ID, sessionId, "等待执行节点");

        sharedLeases.release(sessionId, turn.token());
        ageHeartbeat(turn.turnId(), Duration.ofMinutes(5));

        ChatSessionManager scanner = manager("node-scan", 1);
        assertTrue(scanner.expireStaleTurns(Duration.ofSeconds(30), "测试扫描") >= 1);
        assertEquals(
                ChatTurn.EXPIRED,
                scanner.turn(USER_ID, sessionId, turn.turnId()).status());
    }

    @Test
    @DisplayName("RUNNING 但用户消息缺失的孤儿轮次被标记过期")
    void orphanRunningTurnExpires() throws Exception {
        ChatSessionManager.TurnContext turn = manager.beginTurn(USER_ID, sessionId, "孤儿轮次");
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM chat_messages WHERE message_id = ?")) {
            statement.setLong(1, turn.userMessageId());
            statement.executeUpdate();
        }

        assertTrue(manager.expireStaleTurns(Duration.ofHours(1), "测试扫描") >= 1);
        assertEquals(
                ChatTurn.EXPIRED,
                manager.turn(USER_ID, sessionId, turn.turnId()).status());
    }

    @Test
    @DisplayName("同一轮次重复完成只落一条助手消息")
    void duplicateCompleteIsIdempotent() {
        ChatSessionManager.TurnContext turn =
                manager.beginTurn(USER_ID, sessionId, "算一下工资");
        PayrollChat.Reply reply = new PayrollChat.Reply("已核算", List.of(), "stub");

        Long first = manager.completeTurn(turn, reply);
        Long second = manager.completeTurn(turn, reply);

        assertNotNull(first);
        assertEquals(first, second);
        long assistants =
                chatStore.messages(sessionId).stream()
                        .filter(message -> "ASSISTANT".equals(message.role()))
                        .count();
        assertEquals(1L, assistants);
    }

    @Test
    @DisplayName("开启轮次时用户消息与轮次行同事务落库")
    void beginTurnWritesMessageAndTurnTogether() {
        ChatSessionManager.TurnContext turn =
                manager.beginTurn(USER_ID, sessionId, "事务检查");

        ChatTurn saved = manager.turn(USER_ID, sessionId, turn.turnId());
        assertTrue(saved.userMessageId() > 0);
        assertNotNull(saved.heartbeatAt());
        assertTrue(
                chatStore.messages(sessionId).stream()
                        .anyMatch(
                                message ->
                                        message.id() == saved.userMessageId()
                                                && "USER".equals(message.role())
                                                && "事务检查".equals(message.content())));
    }
}
