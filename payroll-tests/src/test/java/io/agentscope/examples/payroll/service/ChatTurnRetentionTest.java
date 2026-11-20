package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatTurnRetentionTest {

    private static final long USER_ID = 7L;

    private H2ChatStore chatStore;
    private H2ChatTurnStore turnStore;
    private long sessionId;

    @BeforeEach
    void setUp() {
        PayrollDatabase database =
                PayrollDatabase.inMemory("chat-turn-retention-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        turnStore = new H2ChatTurnStore(database);
        sessionId = chatStore.create(USER_ID, "保留期会话").id();
    }

    @Test
    @DisplayName("超过保留天数的轮次被删除，未过期的保留")
    void deletesOnlyExpiredTurns() {
        String oldTurn = createTurn("turn-old", 100);
        String recentTurn = createTurn("turn-recent", 1);

        ChatSessionManager manager = manager(90, 0);

        assertEquals(1, manager.cleanupExpiredTurns());
        assertTrue(turnStore.find(oldTurn).isEmpty());
        assertTrue(turnStore.find(recentTurn).isPresent());
    }

    @Test
    @DisplayName("保留天数为 0 时不清理")
    void disabledRetentionKeepsEverything() {
        String oldTurn = createTurn("turn-old", 100);

        assertEquals(0, manager(0, 0).cleanupExpiredTurns());
        assertTrue(turnStore.find(oldTurn).isPresent());
    }

    private ChatSessionManager manager(int retentionDays, int cleanupIntervalMinutes) {
        return new ChatSessionManager(
                chatStore,
                (session, user) -> null,
                VectorMemoryService.disabled(),
                StaticRunRecorder.disabled(),
                ShadowTeamService.disabled(),
                ActiveTeamService.disabled(),
                10,
                60,
                null,
                turnStore,
                TurnLeaseService.disabled(),
                new InMemoryTurnEventBus(),
                "node-a",
                10,
                30,
                300,
                retentionDays,
                cleanupIntervalMinutes);
    }

    private String createTurn(String turnId, int ageDays) {
        LocalDateTime started = LocalDateTime.now().minusDays(ageDays);
        turnStore.create(
                new ChatTurn(
                        turnId,
                        sessionId,
                        USER_ID,
                        0L,
                        0L,
                        ChatTurn.SUCCEEDED,
                        "node-a",
                        0L,
                        0L,
                        null,
                        started,
                        started,
                        null,
                        null,
                        started));
        return turnId;
    }
}
