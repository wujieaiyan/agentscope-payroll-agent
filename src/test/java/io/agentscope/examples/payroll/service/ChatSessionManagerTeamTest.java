package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
import io.agentscope.examples.payroll.team.TeamTemplate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatSessionManagerTeamTest {

    private H2ChatStore chatStore;
    private H2AgentTeamStore teamStore;
    private ChatSessionManager manager;
    private long sessionId;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("session-team-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        teamStore = new H2AgentTeamStore(database);
        sessionId = chatStore.create(7L, "团队会话").id();
        TeamSettings settings = new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D);
        ActiveTeamService active =
                new ActiveTeamService(
                        settings, teamStore, null, List::of, TeamLlmClient.disabled(), null);
        manager =
                new ChatSessionManager(
                        chatStore,
                        (session, user) -> new StubChat(),
                        VectorMemoryService.disabled(),
                        new StaticRunRecorder(teamStore, settings),
                        ShadowTeamService.disabled(),
                        active);
    }

    @Test
    @DisplayName("ACTIVE 成功时动态运行成为主运行，静态运行标记取消")
    void activeSuccessBecomesPrimary() {
        manager.begin(7L, sessionId, "帮我核算 EMP001 2026-10 的工资");
        AgentTeamRun staticRun = latestRun(AgentTeamRun.MODE_STATIC);

        long activeRunId = createRun(AgentTeamRun.MODE_ACTIVE, AgentTeamRun.STATUS_SUCCESS);
        PayrollChat.TeamReply team =
                new PayrollChat.TeamReply(
                        activeRunId,
                        "active-plan",
                        AgentTeamRun.MODE_ACTIVE,
                        AgentTeamRun.STATUS_SUCCESS,
                        TeamTemplate.CALC_VALIDATE.id(),
                        null,
                        List.of("calc-agent"));
        Long messageId =
                manager.complete(
                        7L,
                        sessionId,
                        new PayrollChat.Reply("动态答复", List.of(), "stub", List.of(), team));

        assertNotNull(messageId);
        AgentTeamRun primary = teamStore.primaryRun(sessionId, messageId).orElseThrow();
        assertEquals(activeRunId, primary.id());
        assertNull(primary.fallbackOfRunId(), "成功运行不记录回退链路");

        AgentTeamRun cancelled = teamStore.findRun(staticRun.id()).orElseThrow();
        assertEquals(AgentTeamRun.STATUS_CANCELLED, cancelled.status());
        assertEquals(0L, cancelled.messageId(), "被接管的静态运行不绑定消息");
        assertFalse(cancelled.primary());
    }

    @Test
    @DisplayName("ACTIVE 回退时静态运行仍是主运行，回退记录指向它")
    void activeFallbackKeepsStaticPrimary() {
        manager.begin(7L, sessionId, "帮我核算 EMP001 2026-10 的工资");
        AgentTeamRun staticRun = latestRun(AgentTeamRun.MODE_STATIC);

        long activeRunId = createRun(AgentTeamRun.MODE_ACTIVE, AgentTeamRun.STATUS_FALLBACK);
        PayrollChat.TeamReply team =
                new PayrollChat.TeamReply(
                        activeRunId,
                        "active-plan",
                        AgentTeamRun.MODE_ACTIVE,
                        AgentTeamRun.STATUS_FALLBACK,
                        TeamTemplate.CALC_VALIDATE.id(),
                        "L3: 动态流程执行失败",
                        List.of());
        Long messageId =
                manager.complete(
                        7L,
                        sessionId,
                        new PayrollChat.Reply("静态答复", List.of(), "stub", List.of(), team));

        assertNotNull(messageId);
        AgentTeamRun primary = teamStore.primaryRun(sessionId, messageId).orElseThrow();
        assertEquals(staticRun.id(), primary.id());
        assertEquals(AgentTeamRun.STATUS_SUCCESS, primary.status());

        AgentTeamRun fallback = teamStore.findRun(activeRunId).orElseThrow();
        assertEquals(messageId, fallback.messageId());
        assertEquals(staticRun.id(), fallback.fallbackOfRunId());
        assertFalse(fallback.primary());
        assertTrue(fallback.status().equals(AgentTeamRun.STATUS_FALLBACK));
    }

    @Test
    @DisplayName("未进入 ACTIVE 时保持原静态主运行语义")
    void staticReplyStillPrimary() {
        manager.begin(7L, sessionId, "你好");
        Long messageId =
                manager.complete(
                        7L,
                        sessionId,
                        new PayrollChat.Reply("静态答复", List.of(), "stub"));

        assertNotNull(messageId);
        AgentTeamRun primary = teamStore.primaryRun(sessionId, messageId).orElseThrow();
        assertEquals(AgentTeamRun.MODE_STATIC, primary.mode());
        assertEquals(AgentTeamRun.STATUS_SUCCESS, primary.status());
    }

    private AgentTeamRun latestRun(String mode) {
        return teamStore.runsBySession(sessionId).stream()
                .filter(run -> mode.equals(run.mode()))
                .findFirst()
                .orElseThrow();
    }

    private long createRun(String mode, String status) {
        return teamStore.createRun(
                new AgentTeamRun(
                        0L,
                        "active-plan",
                        sessionId,
                        0L,
                        7L,
                        mode,
                        "CALCULATE",
                        TeamTemplate.CALC_VALIDATE.id(),
                        "{}",
                        null,
                        null,
                        status,
                        null,
                        null,
                        null,
                        null,
                        false,
                        0L,
                        LocalDateTime.now(),
                        null,
                        null,
                        null));
    }

    private static final class StubChat implements PayrollChat {

        @Override
        public Reply chat(String message) {
            return new Reply("静态答复", List.of(), "stub");
        }
    }
}
