package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatSessionCacheTest {

    private static final long USER_ID = 7L;

    private H2ChatStore store;
    private AtomicInteger closed;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("session-cache-" + System.nanoTime());
        store = new H2ChatStore(database);
        closed = new AtomicInteger();
    }

    @Test
    @DisplayName("超过缓存上限时淘汰最久未使用的会话并关闭实例")
    void evictsLeastRecentlyUsedWhenOverLimit() {
        long first = store.create(USER_ID, "会话一").id();
        long second = store.create(USER_ID, "会话二").id();
        ChatSessionManager manager = manager(TurnLeaseService.disabled(), 1);

        manager.chatFor(USER_ID, first);
        manager.chatFor(USER_ID, second);

        assertEquals(1, manager.cachedChatCount());
        assertEquals(1, closed.get());
    }

    @Test
    @DisplayName("共享会话锁的另一个实例不能同时处理同一会话")
    void secondInstanceCannotBeginWhileLocked() {
        long sessionId = store.create(USER_ID, "互斥会话").id();
        TurnLeaseService sharedLeases = new TurnLeaseService(null, 30, 10);
        ChatSessionManager first = manager(sharedLeases, 10);
        ChatSessionManager second = manager(sharedLeases, 10);

        first.begin(USER_ID, sessionId, "第一条");
        assertThrows(
                PayrollException.class, () -> second.begin(USER_ID, sessionId, "第二条"));

        first.release(sessionId);
        assertNotNull(second.begin(USER_ID, sessionId, "第二条"));
        second.release(sessionId);
    }

    private ChatSessionManager manager(TurnLeaseService leases, int maxCachedChats) {
        PayrollChatFactory factory =
                (sessionId, userId) ->
                        new PayrollChat() {
                            @Override
                            public Reply chat(String message) {
                                return new Reply("ok", List.of(), "stub");
                            }

                            @Override
                            public void close() {
                                closed.incrementAndGet();
                            }
                        };
        return new ChatSessionManager(
                store,
                factory,
                VectorMemoryService.disabled(),
                StaticRunRecorder.disabled(),
                ShadowTeamService.disabled(),
                ActiveTeamService.disabled(),
                maxCachedChats,
                60,
                null,
                null,
                leases,
                new InMemoryTurnEventBus(),
                "local");
    }
}
