package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2ChatShadowTraceStoreTest {

    private H2ChatStore store;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("shadow-trace-" + System.nanoTime());
        store = new H2ChatStore(database);
        session = store.create(5L, "影子追溯");
    }

    @Test
    @DisplayName("影子追溯以 message_id=0 落库，可按运行读取且不污染消息依据")
    void persistsShadowTracesByRun() {
        ChatToolTrace first =
                trace("call-1", "8000.00")
                        .withScope(
                                new TraceScope(
                                        61L,
                                        "shadow-plan",
                                        "n1",
                                        "calc-agent",
                                        "CALCULATOR",
                                        "SHADOW",
                                        null));
        ChatToolTrace second =
                trace("call-2", "9000.00")
                        .withScope(
                                new TraceScope(
                                        62L,
                                        "shadow-plan",
                                        "n2",
                                        "val-agent",
                                        "VALIDATOR",
                                        "SHADOW",
                                        null));

        List<ChatToolTrace> persisted =
                store.appendTraces(session.id(), 0L, List.of(first, second));

        assertEquals(2, persisted.size());
        assertTrue(persisted.get(0).id() > 0);
        assertEquals(0L, persisted.get(0).messageId());
        assertEquals(session.id(), persisted.get(0).sessionId());

        List<ChatToolTrace> forRun = store.tracesForRun(61L);
        assertEquals(1, forRun.size());
        assertEquals("n1", forRun.get(0).nodeId());
        assertEquals("calc-agent", forRun.get(0).agent());
        assertEquals(new BigDecimal("8000.00"), forRun.get(0).amounts().get("net_pay"));

        assertTrue(store.traces(session.id(), 123L).isEmpty());
        assertEquals(2, store.traces(session.id(), 0L).size());
    }

    private static ChatToolTrace trace(String callId, String netPay) {
        return ChatToolTrace.draft(
                callId,
                "calculate_payroll",
                "calc-agent",
                "{\"employee_id\":\"EMP001\",\"period\":\"2026-10\"}",
                "{\"status\":\"OK\",\"data\":{\"employee_id\":\"EMP001\",\"net_pay\":"
                        + netPay
                        + "}}",
                false,
                "SUCCESS",
                42,
                List.of("EMP001"),
                "2026-10",
                "研发部",
                Map.of("net_pay", new BigDecimal(netPay)));
    }
}
