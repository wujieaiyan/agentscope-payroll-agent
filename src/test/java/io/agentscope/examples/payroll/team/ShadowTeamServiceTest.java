package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShadowTeamServiceTest {

    private H2AgentTeamStore teamStore;
    private H2ChatStore chatStore;
    private long sessionId;
    private ShadowTeamService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("shadow-" + System.nanoTime());
        teamStore = new H2AgentTeamStore(database);
        chatStore = new H2ChatStore(database);
        sessionId = chatStore.create(7L, "影子").id();
        service = create(TeamTestFixtures.builtinCatalog(), TeamMode.SHADOW, 1D);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    @DisplayName("写操作在 L0 直接回退，只落一条影子回退记录")
    void writeActionFallsBackAtL0() {
        ShadowTeamService.ShadowOutcome outcome =
                service.runNow(7L, sessionId, 41L, "确认发薪 EMP001 2026-10", null);

        assertTrue(outcome.recorded());
        assertFalse(outcome.planned());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, outcome.status());
        assertTrue(outcome.fallbackReason().startsWith("L0:"));

        AgentTeamRun run = teamStore.findRun(outcome.runId()).orElseThrow();
        assertEquals(AgentTeamRun.MODE_SHADOW, run.mode());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, run.status());
        assertFalse(run.primary());
        assertEquals(41L, run.messageId());
        assertNotNull(run.closedAt());
        assertFalse(
                teamStore.primaryRun(sessionId, 41L).isPresent(),
                "影子运行不能成为主运行");
    }

    @Test
    @DisplayName("目录为空时在 L2 记录候选筛选回退，不创建计划")
    void emptyCatalogFallsBackAtL2() {
        service.close();
        service = create(List.of(), TeamMode.SHADOW, 1D);

        ShadowTeamService.ShadowOutcome outcome =
                service.runNow(7L, sessionId, 42L, "帮我核算 EMP001 2026-10 的工资", null);

        assertTrue(outcome.recorded());
        assertFalse(outcome.planned());
        assertTrue(outcome.fallbackReason().startsWith("L2:"));
        assertTrue(outcome.fallbackReason().contains("角色无可用候选"));

        AgentTeamRun run = teamStore.findRun(outcome.runId()).orElseThrow();
        assertEquals(TeamTemplate.CALC_VALIDATE.id(), run.templateId());
        assertEquals(TeamIntent.TASK_CALCULATE, run.taskType());
        assertEquals("{}", run.planJson());
        assertTrue(run.catalogSnapshotJson().contains("\"agents\""));
        assertNotNull(run.closedAt());
    }

    @Test
    @DisplayName("计划成功但执行实例不可用时落 L3 回退，并保留基线与计划")
    void executionFailureFallsBackAtL3() {
        ShadowTeamService.ShadowOutcome outcome =
                service.runNow(7L, sessionId, 43L, "帮我核算 EMP001 2026-10 的工资", 99L);

        assertTrue(outcome.recorded());
        assertTrue(outcome.planned());
        assertEquals(AgentTeamRun.STATUS_FALLBACK, outcome.status());
        assertTrue(outcome.fallbackReason().contains("L3"));

        AgentTeamRun run = teamStore.findRun(outcome.runId()).orElseThrow();
        assertEquals(99L, run.baselineRunId());
        assertTrue(run.planJson().contains("\"nodes\""));
        assertTrue(run.planJson().contains("calc-agent"));
        assertNotNull(run.closedAt());
    }

    @Test
    @DisplayName("非 SHADOW 模式或采样率为 0 时服务关闭")
    void disabledOutsideShadowOrZeroRate() {
        service.close();
        service = create(TeamTestFixtures.builtinCatalog(), TeamMode.STATIC, 1D);
        assertFalse(service.enabled());
        ShadowTeamService.ShadowOutcome skipped =
                service.runNow(7L, sessionId, 44L, "帮我核算工资", null);
        assertEquals("SKIPPED", skipped.status());
        assertEquals("MODE_NOT_SHADOW", skipped.fallbackReason());

        service.close();
        service = create(TeamTestFixtures.builtinCatalog(), TeamMode.SHADOW, 0D);
        assertFalse(service.enabled());
        assertFalse(service.runNow(7L, sessionId, 45L, "帮我核算工资", null).recorded());
    }

    @Test
    @DisplayName("SHADOW 且采样率大于 0 时启用")
    void enabledInShadowMode() {
        assertTrue(service.enabled());
        assertEquals(TeamMode.SHADOW, TeamMode.from("shadow"));
    }

    private ShadowTeamService create(
            List<AgentDefinition> catalog, TeamMode mode, double sampleRate) {
        Supplier<List<AgentDefinition>> supplier = () -> catalog;
        return new ShadowTeamService(
                new TeamSettings(mode, 180, 30, sampleRate),
                teamStore,
                chatStore,
                null,
                supplier,
                (session, user) -> {
                    throw new IllegalStateException("stub chat unavailable");
                },
                TeamLlmClient.disabled(),
                new Random(0L));
    }
}
