package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2ChatContextWindowTest {

    private static final long USER_ID = 7L;

    private H2ChatStore store;
    private long sessionId;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("chat-window-" + System.nanoTime());
        store = new H2ChatStore(database);
        sessionId = store.create(USER_ID, "窗口会话").id();
        for (int i = 1; i <= 10; i++) {
            store.append(sessionId, "USER", "问题" + i, List.of(), "test");
            store.append(sessionId, "ASSISTANT", "回答" + i, List.of(), "test");
        }
    }

    @Test
    @DisplayName("水位之后只取最近 N 条消息并按时间升序返回")
    void messagesRecentReturnsAscendingWindow() {
        List<ChatMessage> messages = store.messagesRecent(sessionId, 16L, 4);

        assertEquals(List.of(17L, 18L, 19L, 20L), ids(messages));
        assertEquals("问题9", messages.get(0).content());
    }

    @Test
    @DisplayName("上滑翻页按 beforeId 取更早一页，仍按升序")
    void messagesBeforePagesBackwards() {
        assertEquals(List.of(8L, 9L, 10L), ids(store.messagesBefore(sessionId, 11L, 3)));
        assertEquals(List.of(5L, 6L, 7L), ids(store.messagesBefore(sessionId, 8L, 3)));
    }

    @Test
    @DisplayName("按 id 读取消息限定在当前会话内")
    void messageIsScopedToSession() {
        long other = store.create(USER_ID, "另一会话").id();

        assertTrue(store.message(sessionId, 3L).isPresent());
        assertFalse(store.message(other, 3L).isPresent());
    }

    @Test
    @DisplayName("上下文摘要与水位随 saveContext 一起持久化")
    void contextRoundTripKeepsWatermark() {
        store.saveContext(sessionId, new ChatContextSnapshot("早期摘要", 3, 6));

        ChatContextSnapshot loaded = store.loadContext(sessionId);
        assertEquals("早期摘要", loaded.summary());
        assertEquals(3, loaded.summarizedTurns());
        assertEquals(6L, loaded.throughMessageId());
    }

    private static List<Long> ids(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::id).toList();
    }
}
