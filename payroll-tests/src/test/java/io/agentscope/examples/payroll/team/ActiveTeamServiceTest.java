package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.service.PayrollChat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActiveTeamServiceTest {

    private H2AgentTeamStore teamStore;
    private long sessionId;
    private ActiveTeamService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("active-" + System.nanoTime());
        teamStore = new H2AgentTeamStore(database);
        sessionId = new H2ChatStore(database).create(7L, "正式团队").id();
        service = create(new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D), TeamTestFixtures.builtinCatalog());
    }

    @Test
    @DisplayName("ACTIVE 白名单按用户与会话同时生效，空集合表示全部放行")
    void whitelistGatesActiveAttempts() {
        assertEquals(true, service.allowed(7L, sessionId));
        assertTrue(service.allowed(8L, sessionId));
        ActiveTeamService userScoped =
                create(
                        new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D, Set.of(7L), Set.of()),
                        TeamTestFixtures.builtinCatalog());
        assertTrue(userScoped.allowed(7L, sessionId));
        assertFalse(userScoped.allowed(8L, sessionId));

        ActiveTeamService sessionScoped =
                create(
                        new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D, Set.of(), Set.of(999L)),
                        TeamTestFixtures.builtinCatalog());
        assertFalse(sessionScoped.allowed(7L, sessionId));
    }

    @Test
    @DisplayName("非 ACTIVE 模式不进入动态链路")
    void staticModeSkips() {
        ActiveTeamService staticOnly =
                create(new TeamSettings(TeamMode.STATIC, 180, 30, 0D), TeamTestFixtures.builtinCatalog());
        assertFalse(staticOnly.enabled());
        ActiveTeamService.Attempt attempt =
                staticOnly.attempt(null, 7L, sessionId, "帮我核算工资", "", null);
        assertFalse(attempt.recorded());
        assertEquals("SKIPPED", attempt.status());
    }

    @Test
    @DisplayName("写操作在 L0 落 ACTIVE 回退记录，不创建主运行")
    void writeActionRecordsActiveFallback() {
        ActiveTeamService.Attempt attempt =
                service.attempt(null, 7L, sessionId, "确认发薪 EMP001 2026-10", "", null);

        assertTrue(attempt.recorded());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, attempt.status());
        assertTrue(attempt.fallbackReason().startsWith("L0:"));
        assertFalse(attempt.succeeded());

        AgentTeamRun run = teamStore.findRun(attempt.runId()).orElseThrow();
        assertEquals(AgentTeamRun.MODE_ACTIVE, run.mode());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, run.status());
        assertFalse(run.primary());
        assertEquals(sessionId, run.sessionId());
    }

    @Test
    @DisplayName("目录为空时在 L2 落 ACTIVE 回退记录并写审计快照")
    void emptyCatalogRecordsL2Fallback() {
        ActiveTeamService empty =
                create(new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D), List.of());
        ActiveTeamService.Attempt attempt =
                empty.attempt(null, 7L, sessionId, "帮我核算 EMP001 2026-10 的工资", "", null);

        assertTrue(attempt.recorded());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, attempt.status());
        assertTrue(attempt.fallbackReason().startsWith("L2:"));
        assertEquals(TeamTemplate.CALC_VALIDATE.id(), attempt.templateId());
    }

    @Test
    @DisplayName("成功运行绑定消息并成为主运行")
    void completeSuccessMarksPrimary() {
        long runId = createRun(AgentTeamRun.STATUS_SUCCESS);
        PayrollChat.TeamReply team =
                new PayrollChat.TeamReply(
                        runId,
                        "active-plan",
                        AgentTeamRun.MODE_ACTIVE,
                        AgentTeamRun.STATUS_SUCCESS,
                        TeamTemplate.CALC_VALIDATE.id(),
                        null,
                        List.of("calc-agent"));

        service.completeSuccess(team, 55L, List.of(), List.of());

        AgentTeamRun run = teamStore.findRun(runId).orElseThrow();
        assertEquals(55L, run.messageId());
        assertTrue(run.primary());
        assertTrue(teamStore.primaryRun(sessionId, 55L).isPresent());
    }

    @Test
    @DisplayName("回退运行补绑消息并指向实际执行的静态运行")
    void completeFallbackLinksStaticRun() {
        long staticRunId = createRun(AgentTeamRun.STATUS_SUCCESS);
        long activeRunId = createRun(AgentTeamRun.STATUS_RUNNING);
        PayrollChat.TeamReply team =
                new PayrollChat.TeamReply(
                        activeRunId,
                        "active-plan",
                        AgentTeamRun.MODE_ACTIVE,
                        AgentTeamRun.STATUS_FALLBACK,
                        TeamTemplate.CALC_VALIDATE.id(),
                        "L3: 动态流程执行失败",
                        List.of());

        service.completeFallback(team, 66L, staticRunId);

        AgentTeamRun active = teamStore.findRun(activeRunId).orElseThrow();
        assertEquals(66L, active.messageId());
        assertEquals(staticRunId, active.fallbackOfRunId());
        assertFalse(active.primary());
    }

    private long createRun(String status) {
        return teamStore.createRun(
                new AgentTeamRun(
                        0L,
                        "active-plan",
                        sessionId,
                        0L,
                        7L,
                        AgentTeamRun.MODE_ACTIVE,
                        TeamIntent.TASK_CALCULATE,
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

    private ActiveTeamService create(TeamSettings settings, List<AgentDefinition> catalog) {
        return new ActiveTeamService(settings, teamStore, null, () -> catalog, TeamLlmClient.disabled(), null);
    }
}
