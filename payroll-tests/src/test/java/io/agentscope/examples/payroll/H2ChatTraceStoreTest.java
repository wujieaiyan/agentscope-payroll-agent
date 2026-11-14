package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.domain.TraceScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2ChatTraceStoreTest {

    private H2ChatStore store;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("chat-trace-" + System.nanoTime());
        store = new H2ChatStore(database);
    }

    @Test
    @DisplayName("助手消息与工具追溯同事务落库，可按消息查询")
    void savesAndLoadsTraces() {
        ChatSession session = store.create(1L, "追溯会话");
        ChatToolTrace trace = trace("call-1", "calculate_payroll", "21000.50");

        ChatMessage saved =
                store.appendAssistant(
                        session.id(),
                        "张伟实发工资 21000.50 元",
                        List.of("calculate_payroll"),
                        "stub:test",
                        List.of(trace));

        assertTrue(saved.id() > 0);
        assertEquals(H2ChatStore.ROLE_ASSISTANT, saved.role());

        List<ChatToolTrace> loaded = store.traces(session.id(), saved.id());
        assertEquals(1, loaded.size());
        ChatToolTrace item = loaded.get(0);
        assertEquals("call-1", item.toolCallId());
        assertEquals("calculate_payroll", item.toolName());
        assertEquals("calculator", item.agent());
        assertEquals("SUCCESS", item.resultState());
        assertEquals(42, item.durationMs());
        assertEquals(List.of("EMP001"), item.employees());
        assertEquals("2026-10", item.period());
        assertEquals("研发部", item.department());
        assertEquals(new BigDecimal("21000.50"), item.amounts().get("net_pay"));
        assertEquals(saved.id(), item.messageId());
        assertEquals(session.id(), item.sessionId());
        assertTrue(item.argsJson().contains("EMP001"));
        assertTrue(item.resultJson().contains("OK"));
    }

    @Test
    @DisplayName("不同会话无法读取他人消息的依据")
    void isolatesTracesBySession() {
        ChatSession first = store.create(1L, "会话一");
        ChatSession second = store.create(2L, "会话二");
        ChatMessage saved =
                store.appendAssistant(
                        first.id(), "回答", List.of(), "stub:test", List.of(trace("call-1", "t", "1")));

        assertTrue(store.traces(second.id(), saved.id()).isEmpty());
        assertEquals(1, store.traces(first.id(), saved.id()).size());
    }

    @Test
    @DisplayName("清空会话会同时删除追溯，删除会话同样级联")
    void clearsAndDeletesTraces() {
        ChatSession session = store.create(1L, "会话");
        ChatMessage saved =
                store.appendAssistant(
                        session.id(), "回答", List.of(), "stub:test", List.of(trace("call-1", "t", "1")));

        store.clear(session.id());
        assertTrue(store.messages(session.id()).isEmpty());
        assertTrue(store.traces(session.id(), saved.id()).isEmpty());

        ChatMessage again =
                store.appendAssistant(
                        session.id(), "再回答", List.of(), "stub:test", List.of(trace("call-2", "t", "2")));
        assertEquals(1, store.traces(session.id(), again.id()).size());
        store.delete(session.id());
        assertTrue(store.traces(session.id(), again.id()).isEmpty());
    }

    @Test
    @DisplayName("同一阶段 key 在不同运行中按 run_id/node_id 隔离")
    void persistsRunAndNodeAttribution() {
        ChatSession session = store.create(1L, "归因会话");
        ChatToolTrace first =
                trace("call-1", "calculate_payroll", "100")
                        .withScope(
                                new TraceScope(
                                        11L,
                                        "plan-a",
                                        "calculator",
                                        "PayrollCalculatorAgent",
                                        "CALCULATOR",
                                        "STATIC",
                                        "calculator"));
        ChatToolTrace second =
                trace("call-2", "calculate_payroll", "200")
                        .withScope(
                                new TraceScope(
                                        12L,
                                        "plan-b",
                                        "calculator-retry",
                                        "PayrollCalculatorAgent",
                                        "CALCULATOR",
                                        "STATIC",
                                        "calculator"));

        ChatMessage saved =
                store.appendAssistant(
                        session.id(), "回答", List.of(), "stub:test", List.of(first, second));

        List<ChatToolTrace> loaded = store.traces(session.id(), saved.id());
        assertEquals(2, loaded.size());
        assertEquals(11L, loaded.get(0).runId());
        assertEquals("calculator", loaded.get(0).nodeId());
        assertEquals(12L, loaded.get(1).runId());
        assertEquals("calculator-retry", loaded.get(1).nodeId());
        assertEquals("calculator", loaded.get(1).agent());

        ChatToolTrace unbound = trace("call-3", "t", "1");
        assertEquals(0L, unbound.runId());
        assertNull(unbound.nodeId());
    }

    @Test
    @DisplayName("知识库引用随助手消息落库并在读取时还原")
    void persistsCitations() {
        ChatSession session = store.create(1L, "引用会话");
        Citation citation =
                new Citation(
                        1,
                        "K-ANNUAL",
                        7L,
                        "人事制度",
                        11L,
                        "员工手册.pdf",
                        101L,
                        5,
                        "第三章 > 休假",
                        0.82,
                        List.of("keyword", "vector"),
                        "员工年假按工龄计算。");

        ChatMessage saved =
                store.appendAssistant(
                        session.id(),
                        "员工年假按工龄计算。",
                        List.of(),
                        "stub:test",
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(citation));

        List<ChatMessage> messages = store.messages(session.id());
        assertEquals(1, messages.size());
        ChatMessage loaded = messages.get(0);
        assertEquals(saved.id(), loaded.id());
        assertEquals(1, loaded.citations().size());
        Citation restored = loaded.citations().get(0);
        assertEquals(1, restored.index());
        assertEquals("K-ANNUAL", restored.pointId());
        assertEquals(7L, restored.seriesId());
        assertEquals("人事制度", restored.seriesName());
        assertEquals(11L, restored.documentId());
        assertEquals(101L, restored.chunkId());
        assertEquals(5, restored.pageNo());
        assertEquals("第三章 > 休假", restored.titlePath());
        assertEquals(0.82, restored.score(), 0.0001);
        assertEquals(List.of("keyword", "vector"), restored.routes());
        assertEquals("员工年假按工龄计算。", restored.snippet());
    }

    @Test
    @DisplayName("未携带引用的消息读取为空列表")
    void emptyCitationsStayEmpty() {
        ChatSession session = store.create(1L, "普通会话");
        store.appendAssistant(session.id(), "回答", List.of(), "stub:test", List.of());

        assertTrue(store.messages(session.id()).get(0).citations().isEmpty());
    }

    private static ChatToolTrace trace(String callId, String toolName, String netPay) {
        return ChatToolTrace.draft(
                callId,
                toolName,
                "calculator",
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
