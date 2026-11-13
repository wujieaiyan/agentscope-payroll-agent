package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.ToolTraceRecorder.Entry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolTraceRecorderTest {

    private static final String RESULT =
            """
            {"status":"OK","data":{"employee_id":"EMP001","period":"2026-10","net_pay":21000.50}}
            """;

    @Test
    @DisplayName("同名工具按调用顺序配对，不同工具互不影响")
    void pairsEntriesInCallOrder() {
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        recorder.record("calculate_payroll", "{\"employee_id\":\"EMP001\"}", RESULT, 12);
        recorder.record("list_employees", "{}", "{\"status\":\"OK\",\"data\":[]}", 3);
        recorder.record("calculate_payroll", "{\"employee_id\":\"EMP002\"}", RESULT, 20);

        Entry first = recorder.take("calculate_payroll");
        assertNotNull(first);
        assertTrue(first.argsJson().contains("EMP001"));
        assertEquals(12, first.durationMs());
        assertEquals(List.of("EMP001"), first.employees());
        assertEquals("2026-10", first.period());
        assertEquals(0, new BigDecimal("21000.50").compareTo(first.amounts().get("net_pay")));

        Entry second = recorder.take("calculate_payroll");
        assertNotNull(second);
        assertTrue(second.argsJson().contains("EMP002"));

        assertNotNull(recorder.take("list_employees"));
        assertEquals(0, recorder.pendingCount());
        assertNull(recorder.take("calculate_payroll"));
    }

    @Test
    @DisplayName("外部工具没有记录时返回 null，不影响事件配对")
    void returnsNullForUnrecordedTool() {
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        assertNull(recorder.take("mcp__external__lookup"));
        assertNull(recorder.take(null));
    }

    @Test
    @DisplayName("超过 8KB 的结果截断并标记，金额仍从完整结果提取")
    void truncatesLargeResultsButKeepsAmounts() {
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        String padding = "x".repeat(ToolTraceRecorder.MAX_RESULT_CHARS);
        String result =
                "{\"status\":\"OK\",\"data\":{\"padding\":\""
                        + padding
                        + "\",\"employee_id\":\"EMP009\",\"net_pay\":9999.99}}";

        recorder.record("calculate_payroll", "{}", result, 1);
        Entry entry = recorder.take("calculate_payroll");

        assertNotNull(entry);
        assertTrue(entry.truncated());
        assertTrue(entry.resultJson().length() > ToolTraceRecorder.MAX_RESULT_CHARS);
        assertTrue(entry.resultJson().endsWith("…（已截断）"));
        assertEquals(List.of("EMP009"), entry.employees());
        assertEquals(0, new BigDecimal("9999.99").compareTo(entry.amounts().get("net_pay")));
    }

    @Test
    @DisplayName("clear 清空未配对记录，阶段之间互不串扰")
    void clearsPendingEntries() {
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        recorder.record("calculate_payroll", "{}", RESULT, 1);
        recorder.clear();
        assertEquals(0, recorder.pendingCount());
        assertNull(recorder.take("calculate_payroll"));
        assertFalse(recorder.pendingCount() > 0);
    }
}
