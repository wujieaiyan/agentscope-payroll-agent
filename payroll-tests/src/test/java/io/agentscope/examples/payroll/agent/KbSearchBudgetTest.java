package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbSearchBudgetTest {

    @Test
    @DisplayName("相同缓存键复用首次结果，不重复占用额度")
    void repeatedKeyReusesCachedResult() {
        KbSearchBudget budget = new KbSearchBudget();

        KbSearchBudget.Slot first = budget.begin("q|3|5");
        budget.succeed("q|3|5", "{\"status\":\"OK\"}");
        KbSearchBudget.Slot second = budget.begin("q|3|5");

        assertTrue(first.allowed());
        assertFalse(first.cached());
        assertTrue(second.allowed());
        assertTrue(second.cached());
        assertEquals("{\"status\":\"OK\"}", second.result());
        assertEquals(1, budget.used());
    }

    @Test
    @DisplayName("不同请求合计最多 3 次，超出后拒绝")
    void distinctKeysShareLimit() {
        KbSearchBudget budget = new KbSearchBudget();

        assertTrue(budget.begin("a").allowed());
        assertTrue(budget.begin("b").allowed());
        assertTrue(budget.begin("c").allowed());
        KbSearchBudget.Slot fourth = budget.begin("d");

        assertFalse(fourth.allowed());
        assertFalse(fourth.cached());
        assertEquals(KbSearchBudget.MAX_SEARCHES, budget.used());
    }

    @Test
    @DisplayName("失败响应不进缓存，仍占用额度")
    void unsuccessfulResultIsNotCached() {
        KbSearchBudget budget = new KbSearchBudget();
        budget.begin("q");

        budget.succeed("q", "");
        KbSearchBudget.Slot retry = budget.begin("q");

        assertTrue(retry.allowed());
        assertFalse(retry.cached());
        assertEquals(2, budget.used());
    }

    @Test
    @DisplayName("clear 后额度与缓存同时复位")
    void clearResetsTurnState() {
        KbSearchBudget budget = new KbSearchBudget(1);
        budget.begin("q");
        budget.succeed("q", "{\"status\":\"OK\"}");
        assertEquals(1, budget.used());

        budget.clear();

        KbSearchBudget.Slot afterClear = budget.begin("q");
        assertTrue(afterClear.allowed());
        assertFalse(afterClear.cached());
        assertEquals(1, budget.used());
    }
}
