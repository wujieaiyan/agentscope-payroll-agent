package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.examples.payroll.db.H2LlmUsageStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBudgetServiceTest {

    private static final long USER_ID = 7L;
    private static final long OTHER_USER = 8L;

    private H2LlmUsageStore store;

    @BeforeEach
    void setUp() {
        store = new H2LlmUsageStore(PayrollDatabase.inMemory("token-budget-" + System.nanoTime()));
    }

    @Test
    @DisplayName("预算为 0 时不查询用量也不拒绝")
    void disabledBudgetSkipsStore() {
        LlmUsageStore unused = mock(LlmUsageStore.class);
        TokenBudgetService service = new TokenBudgetService(unused, 0);

        assertDoesNotThrow(() -> service.check(USER_ID));
        verifyNoInteractions(unused);
    }

    @Test
    @DisplayName("系统用户与非法用户 ID 直接放行")
    void systemUserBypassesBudget() {
        TokenBudgetService service = new TokenBudgetService(store, 10);

        assertDoesNotThrow(() -> service.check(0));
        assertDoesNotThrow(() -> service.check(-1));
    }

    @Test
    @DisplayName("当日用量未达预算时放行，达到预算后 429")
    void checkRejectsWhenBudgetExhausted() {
        insert(USER_ID, 60);
        TokenBudgetService service = new TokenBudgetService(store, 100);

        assertDoesNotThrow(() -> service.check(USER_ID));

        insert(USER_ID, 40);
        assertEquals(100, service.usedToday(USER_ID, true));
        TooManyRequestsException error =
                assertThrows(TooManyRequestsException.class, () -> service.check(USER_ID));
        assertTrue(error.getMessage().contains("100/100"));
    }

    @Test
    @DisplayName("30 秒缓存只影响拒绝时机：强制刷新能看到最新用量")
    void cachedUsageHidesNewFactsUntilRefresh() {
        insert(USER_ID, 60);
        TokenBudgetService service = new TokenBudgetService(store, 100);

        assertEquals(60, service.usedToday(USER_ID, false));

        insert(USER_ID, 40);
        assertEquals(60, service.usedToday(USER_ID, false));
        assertDoesNotThrow(() -> service.check(USER_ID));

        assertEquals(100, service.usedToday(USER_ID, true));
        assertThrows(TooManyRequestsException.class, () -> service.check(USER_ID));
    }

    @Test
    @DisplayName("预算按用户隔离")
    void budgetIsPerUser() {
        insert(OTHER_USER, 500);
        TokenBudgetService service = new TokenBudgetService(store, 100);

        assertDoesNotThrow(() -> service.check(USER_ID));
        assertThrows(TooManyRequestsException.class, () -> service.check(OTHER_USER));
    }

    private void insert(long userId, int tokens) {
        store.insert(
                new LlmUsageRecord(
                        0L,
                        LocalDateTime.now(),
                        Long.toString(userId),
                        "42",
                        "control",
                        "薪酬助手",
                        "assistant",
                        "chat",
                        null,
                        "qwen2.5:3b",
                        "ollama",
                        "control",
                        tokens,
                        0,
                        0,
                        tokens,
                        120L,
                        true,
                        null,
                        "reply-" + tokens));
    }
}
