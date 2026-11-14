package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.StaticRunRecorder;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StaticRunRecorderTest {

    private H2ChatStore chatStore;
    private H2AgentTeamStore teamStore;
    private StubChat chat;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("static-run-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        teamStore = new H2AgentTeamStore(database);
        chat = new StubChat(false);
    }

    @Test
    @DisplayName("STATIC 模式一轮对话产出 run、节点与 trace 归因")
    void recordsRunNodesAndTraceAttribution() {
        ChatSessionManager manager = manager(TeamMode.STATIC, chat);
        ChatSession session = manager.create(1L, "静态运行");

        PayrollChat payrollChat = manager.begin(1L, session.id(), "算一下张伟 2026-10 的工资");
        assertNotNull(chat.scope);
        assertEquals(AgentTeamRun.MODE_STATIC, chat.scope.mode());
        PayrollChat.Reply reply = payrollChat.chat("算一下张伟 2026-10 的工资");
        Long messageId = manager.complete(1L, session.id(), reply);
        manager.release(session.id());

        List<AgentTeamRun> runs = teamStore.runsBySession(session.id());
        assertEquals(1, runs.size());
        AgentTeamRun run = runs.get(0);
        assertEquals(AgentTeamRun.STATUS_SUCCESS, run.status());
        assertEquals(messageId, run.messageId());
        assertTrue(run.primary());
        assertEquals(chat.scope.runId(), run.id());
        assertNotNull(run.closedAt());

        List<AgentRunRecord> records = teamStore.records(run.id());
        assertEquals(3, records.size());
        AgentRunRecord calculator = records.get(0);
        assertEquals("calculator", calculator.stageKey());
        assertEquals(1, calculator.toolCalls());
        assertEquals(1, calculator.traceIds().size());
        assertEquals(AgentRunRecord.STATUS_SKIPPED, records.get(1).status());
        assertEquals(AgentRunRecord.STATUS_SUCCESS, records.get(2).status());

        List<ChatToolTrace> traces = chatStore.traces(session.id(), messageId);
        assertEquals(1, traces.size());
        assertEquals(run.id(), traces.get(0).runId());
        assertEquals("calculator", traces.get(0).nodeId());
        assertEquals(records.get(0).traceIds().get(0), traces.get(0).id());
    }

    @Test
    @DisplayName("对话失败时 run 与失败节点同样落库")
    void recordsFailedRun() {
        StubChat failing = new StubChat(true);
        ChatSessionManager manager = manager(TeamMode.STATIC, failing);
        ChatSession session = manager.create(2L, "失败运行");

        PayrollChat payrollChat = manager.begin(2L, session.id(), "算工资");
        try {
            payrollChat.chat("算工资");
        } catch (PayrollException expected) {
            manager.fail(2L, session.id(), expected.getMessage());
        }
        manager.release(session.id());

        List<AgentTeamRun> runs = teamStore.runsBySession(session.id());
        assertEquals(1, runs.size());
        AgentTeamRun run = runs.get(0);
        assertEquals(AgentTeamRun.STATUS_FAILED, run.status());
        assertEquals("boom", run.fallbackReason());
        assertTrue(run.messageId() > 0);

        List<AgentRunRecord> records = teamStore.records(run.id());
        assertEquals(1, records.size());
        assertEquals(AgentRunRecord.STATUS_FAILED, records.get(0).status());
        assertEquals("boom", records.get(0).errorMessage());
    }

    @Test
    @DisplayName("OFF 模式不产生任何团队运行记录")
    void offModeWritesNothing() {
        ChatSessionManager manager = manager(TeamMode.OFF, chat);
        ChatSession session = manager.create(3L, "关闭");

        PayrollChat payrollChat = manager.begin(3L, session.id(), "算工资");
        PayrollChat.Reply reply = payrollChat.chat("算工资");
        manager.complete(3L, session.id(), reply);
        manager.release(session.id());

        assertTrue(teamStore.runsBySession(session.id()).isEmpty());
        assertEquals(0, teamStore.records(0L).size());
    }

    private ChatSessionManager manager(TeamMode mode, StubChat chat) {
        StaticRunRecorder recorder =
                new StaticRunRecorder(
                        mode == TeamMode.OFF ? null : teamStore,
                        new TeamSettings(mode, 180, 30, 0.15));
        return new ChatSessionManager(
                chatStore,
                (sessionId, userId) -> chat,
                VectorMemoryService.disabled(),
                recorder);
    }

    private static final class StubChat implements PayrollChat {

        private final boolean fail;
        private TraceScope scope;
        private List<StageRun> stages = List.of();

        private StubChat(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void useRunScope(TraceScope scope) {
            this.scope = scope;
        }

        @Override
        public List<StageRun> stageRuns() {
            return stages;
        }

        @Override
        public Reply chat(String message) {
            if (fail) {
                stages =
                        List.of(
                                new StageRun(
                                        "calculator",
                                        "calculator",
                                        "PayrollCalculatorAgent",
                                        "CALCULATOR",
                                        "FAILED",
                                        0,
                                        0,
                                        null,
                                        0,
                                        15,
                                        "boom"));
                throw new PayrollException("boom");
            }
            stages =
                    List.of(
                            new StageRun(
                                    "calculator",
                                    "calculator",
                                    "PayrollCalculatorAgent",
                                    "CALCULATOR",
                                    "SUCCESS",
                                    1,
                                    0,
                                    null,
                                    12,
                                    50,
                                    null),
                            new StageRun(
                                    "validator",
                                    "validator",
                                    "PayrollValidatorAgent",
                                    "VALIDATOR",
                                    "SKIPPED",
                                    0,
                                    0,
                                    null,
                                    10,
                                    0,
                                    null),
                            new StageRun(
                                    "accountant",
                                    "accountant",
                                    "PayrollAccountantAgent",
                                    "ACCOUNTANT",
                                    "SUCCESS",
                                    0,
                                    0,
                                    null,
                                    20,
                                    30,
                                    null));
            TraceScope runScope = scope == null ? TraceScope.none() : scope;
            ChatToolTrace trace =
                    ChatToolTrace.draft(
                                    "call-1",
                                    "calculate_payroll",
                                    "calculator",
                                    "{}",
                                    "{\"status\":\"OK\"}",
                                    false,
                                    "SUCCESS",
                                    42,
                                    List.of("EMP001"),
                                    "2026-10",
                                    "研发部",
                                    Map.of())
                            .withScope(
                                    new TraceScope(
                                            runScope.runId(),
                                            runScope.planId(),
                                            "calculator",
                                            "PayrollCalculatorAgent",
                                            "CALCULATOR",
                                            runScope.mode(),
                                            "calculator"));
            return new Reply("张伟实发 21000.50", List.of("calculate_payroll"), "stub", List.of(trace));
        }
    }
}
