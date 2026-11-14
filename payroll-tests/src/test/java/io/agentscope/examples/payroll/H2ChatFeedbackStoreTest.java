package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2ChatFeedbackStoreTest {

    private H2ChatFeedbackStore store;

    @BeforeEach
    void setUp() {
        store = new H2ChatFeedbackStore(PayrollDatabase.inMemory("feedback-" + System.nanoTime()));
    }

    @Test
    @DisplayName("同一用户对同一消息重复提交只更新原记录")
    void upsertKeepsOneRecordPerUserAndMessage() {
        ChatFeedback first = store.upsert(101L, 7L, 3L, "UP", List.of("SLOW"), null);
        ChatFeedback second =
                store.upsert(101L, 7L, 3L, "DOWN", List.of("WRONG_AMOUNT"), "金额不对");

        assertEquals(first.id(), second.id());
        assertEquals(ChatFeedback.RATING_DOWN, second.rating());
        assertEquals(List.of("WRONG_AMOUNT"), second.tags());
        assertEquals("金额不对", second.comment());
        assertEquals(1, store.list(101L).size());
        assertEquals(second.id(), store.find(101L, 3L).orElseThrow().id());
    }

    @Test
    @DisplayName("同一消息可保留多个用户的反馈")
    void listsFeedbackFromDifferentUsers() {
        store.upsert(201L, 8L, 1L, "UP", List.of(), null);
        store.upsert(201L, 8L, 2L, "DOWN", List.of("INCOMPLETE"), "缺明细");

        List<ChatFeedback> feedback = store.list(201L);
        assertEquals(2, feedback.size());
        assertEquals(1L, feedback.get(0).userId());
        assertEquals(2L, feedback.get(1).userId());
        assertTrue(store.list(999L).isEmpty());
    }

    @Test
    @DisplayName("删除会话反馈按 session 清理")
    void deletesBySession() {
        store.upsert(301L, 9L, 1L, "UP", List.of(), null);
        store.upsert(302L, 9L, 1L, "DOWN", List.of(), null);
        store.upsert(303L, 10L, 1L, "UP", List.of(), null);

        assertEquals(2, store.deleteBySession(9L));
        assertTrue(store.list(301L).isEmpty());
        assertTrue(store.list(302L).isEmpty());
        assertEquals(1, store.list(303L).size());
    }
}
