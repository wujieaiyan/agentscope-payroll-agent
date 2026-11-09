package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatSession;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2AgentTeamStoreTest {

    private H2AgentTeamStore store;
    private H2ChatStore chatStore;
    private H2ChatFeedbackStore feedbackStore;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("team-" + System.nanoTime());
        store = new H2AgentTeamStore(database);
        chatStore = new H2ChatStore(database);
        feedbackStore = new H2ChatFeedbackStore(database);
    }

    @Test
    @DisplayName("运行可按消息查询，主运行在同一消息内唯一")
    void savesRunsAndKeepsSinglePrimary() {
        ChatSession session = chatStore.create(7L, "团队运行");
        long first =
                store.createRun(
                        AgentTeamRun.plannedStatic(
                                session.id(), 11L, 7L, "plan-11", "{\"nodes\":[]}"));
        long second =
                store.createRun(
                        AgentTeamRun.plannedStatic(
                                session.id(), 11L, 7L, "plan-11-retry", "{\"nodes\":[]}"));

        store.finishRun(first, AgentTeamRun.STATUS_SUCCESS, null, 1200);
        store.markPrimary(first);

        AgentTeamRun loaded = store.findRun(first).orElseThrow();
        assertEquals(AgentTeamRun.MODE_STATIC, loaded.mode());
        assertEquals(AgentTeamRun.STATUS_SUCCESS, loaded.status());
        assertEquals(1200, loaded.totalMs());
        assertNotNull(loaded.closedAt());
        assertTrue(loaded.primary());

        assertEquals(first, store.primaryRun(session.id(), 11L).orElseThrow().id());
        List<AgentTeamRun> runs = store.runs(session.id(), 11L);
        assertEquals(2, runs.size());
        assertEquals(second, runs.get(0).id());
        assertFalse(store.findRun(second).orElseThrow().primary());

        store.markPrimary(second);
        assertEquals(second, store.primaryRun(session.id(), 11L).orElseThrow().id());
        assertFalse(store.findRun(first).orElseThrow().primary());
    }

    @Test
    @DisplayName("节点记录可回填 trace id 并按 run 读取")
    void savesRecordsAndTraceIds() {
        ChatSession session = chatStore.create(1L, "节点");
        long runId =
                store.createRun(AgentTeamRun.plannedStatic(session.id(), 21L, 1L, "plan-21", "{}"));
        long recordId =
                store.appendRecord(
                        new AgentRunRecord(
                                0L,
                                runId,
                                "calculator",
                                "calculator",
                                "builtin-calculator",
                                "CALCULATOR",
                                AgentRunRecord.STATUS_SUCCESS,
                                2,
                                0,
                                true,
                                320,
                                880,
                                List.of(),
                                "digest",
                                null,
                                null,
                                LocalDateTime.now()));

        store.attachTraceIds(recordId, List.of(101L, 102L));

        List<AgentRunRecord> records = store.records(runId);
        assertEquals(1, records.size());
        AgentRunRecord record = records.get(0);
        assertEquals(recordId, record.id());
        assertEquals("calculator", record.stageKey());
        assertEquals("CALCULATOR", record.role());
        assertEquals(List.of(101L, 102L), record.traceIds());
        assertEquals(Boolean.TRUE, record.validationPassed());
    }

    @Test
    @DisplayName("超过保留期的运行只归档，不物理删除")
    void archivesExpiredRuns() {
        ChatSession session = chatStore.create(1L, "归档");
        LocalDateTime old = LocalDateTime.now().minusDays(10);
        long runId =
                store.createRun(
                        new AgentTeamRun(
                                0L,
                                "old",
                                session.id(),
                                31L,
                                1L,
                                AgentTeamRun.MODE_STATIC,
                                null,
                                null,
                                "{}",
                                null,
                                null,
                                AgentTeamRun.STATUS_SUCCESS,
                                null,
                                null,
                                null,
                                null,
                                true,
                                10,
                                old,
                                old,
                                null,
                                null));
        store.appendRecord(record(runId));

        assertEquals(1, store.archiveExpired(5));
        assertNotNull(store.findRun(runId).orElseThrow().deletedAt());
        assertNotNull(store.findRun(runId).orElseThrow().archivedAt());
        assertTrue(store.runs(session.id(), 31L).isEmpty());
        assertEquals(1, store.records(runId).size());
        assertEquals(0, store.archiveExpired(5));
    }

    @Test
    @DisplayName("清空与删除会话都会级联清理团队数据")
    void cascadesTeamDataOnClearAndDelete() {
        ChatSession cleared = chatStore.create(1L, "清空");
        long clearedRun =
                store.createRun(
                        AgentTeamRun.plannedStatic(cleared.id(), 41L, 1L, "plan-41", "{}"));
        store.appendRecord(record(clearedRun));
        feedbackStore.upsert(41L, cleared.id(), 1L, "UP", List.of(), "好");

        chatStore.clear(cleared.id());
        assertTrue(store.findRun(clearedRun).isEmpty());
        assertEquals(0, store.records(clearedRun).size());
        assertTrue(feedbackStore.list(41L).isEmpty());

        ChatSession deleted = chatStore.create(2L, "删除");
        long deletedRun =
                store.createRun(
                        AgentTeamRun.plannedStatic(deleted.id(), 51L, 2L, "plan-51", "{}"));
        store.appendRecord(record(deletedRun));
        feedbackStore.upsert(51L, deleted.id(), 2L, "DOWN", List.of("SLOW"), "慢");

        chatStore.delete(deleted.id());
        assertTrue(store.findRun(deletedRun).isEmpty());
        assertEquals(0, store.records(deletedRun).size());
        assertTrue(feedbackStore.list(51L).isEmpty());
    }

    private static AgentRunRecord record(long runId) {
        return new AgentRunRecord(
                0L,
                runId,
                "validator",
                "validator",
                "builtin-validator",
                "VALIDATOR",
                AgentRunRecord.STATUS_SKIPPED,
                0,
                0,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                LocalDateTime.now());
    }
}
