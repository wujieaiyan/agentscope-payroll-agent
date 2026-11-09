package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShadowReportServiceTest {

    private H2AgentTeamStore teamStore;
    private H2ChatStore chatStore;
    private ShadowReportService reportService;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("shadow-report-" + System.nanoTime());
        teamStore = new H2AgentTeamStore(database);
        chatStore = new H2ChatStore(database);
        session = chatStore.create(3L, "报告");
        reportService =
                new ShadowReportService(
                        teamStore, new TeamSettings(TeamMode.SHADOW, 180, 30, 0.25D));
    }

    @Test
    @DisplayName("按窗口聚合计划率、成功率、分层回退与相对基线的差异")
    void aggregatesShadowRunsAgainstBaseline() {
        long baselineId =
                teamStore.createRun(
                        AgentTeamRun.plannedStatic(session.id(), 51L, 3L, "baseline", "{}"));
        teamStore.finishRun(baselineId, AgentTeamRun.STATUS_SUCCESS, null, 1000);

        TeamPlan plan =
                TeamPlan.instantiate(
                        "shadow-plan",
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE,
                        Map.of(
                                TeamRole.CALCULATOR, "calc-agent",
                                TeamRole.VALIDATOR, "val-agent"),
                        "",
                        java.util.List.of());
        long successId =
                teamStore.createRun(
                        shadowRun(
                                "shadow-ok",
                                52L,
                                JsonSupport.toJson(plan),
                                TeamTemplate.CALC_VALIDATE.id(),
                                baselineId));
        teamStore.finishRun(successId, AgentTeamRun.STATUS_SUCCESS, null, 2000);

        long fallbackId =
                teamStore.createRun(
                        shadowRun("shadow-fallback", 53L, "{}", null, baselineId));
        teamStore.finishRun(
                fallbackId, AgentTeamRun.STATUS_FALLBACK, "L2: 没有匹配的任务模板", 100);

        ShadowReportService.ShadowReport report = reportService.report(30);

        assertEquals(30, report.windowDays());
        assertEquals(0.25D, report.sampleRate(), 1e-9);
        assertEquals(2, report.total());
        assertEquals(1, report.planned());
        assertEquals(1, report.success());
        assertEquals(1, report.fallback());
        assertEquals(0, report.other());
        assertEquals(0.5D, report.successRate(), 1e-9);
        assertEquals(0.5D, report.fallbackRate(), 1e-9);
        assertEquals(0.5D, report.planRate(), 1e-9);
        assertEquals(2000L, report.avgDurationMs());
        assertEquals(1, report.baselineSamples());
        assertEquals(1D, report.baselineSuccessRate(), 1e-9);
        assertEquals(1000L, report.baselineAvgDurationMs());
        assertEquals(-0.5D, report.deltaSuccessRate(), 1e-9);
        assertEquals(1000L, report.deltaAvgDurationMs());
        assertEquals(Map.of("L2", 1), report.fallbacks());
        assertEquals(Map.of(TeamTemplate.CALC_VALIDATE.id(), 1), report.templates());
        assertEquals(Map.of(TeamIntent.TASK_CALCULATE, 2), report.tasks());
        assertEquals(Map.of("CALCULATOR:calc-agent", 1, "VALIDATOR:val-agent", 1), report.agents());
    }

    @Test
    @DisplayName("窗口内没有影子运行时全部为零，不除零")
    void emptyWindowReturnsZeros() {
        ShadowReportService.ShadowReport report = reportService.report(7);

        assertEquals(7, report.windowDays());
        assertEquals(0, report.total());
        assertEquals(0L, report.avgDurationMs());
        assertEquals(0D, report.successRate(), 1e-9);
        assertEquals(0D, report.deltaSuccessRate(), 1e-9);
        assertEquals(0L, report.deltaAvgDurationMs());
        assertEquals(Map.of(), report.fallbacks());
        assertEquals(Map.of(), report.agents());
    }

    private AgentTeamRun shadowRun(
            String planId, long messageId, String planJson, String templateId, Long baselineId) {
        return new AgentTeamRun(
                0L,
                planId,
                session.id(),
                messageId,
                3L,
                AgentTeamRun.MODE_SHADOW,
                TeamIntent.TASK_CALCULATE,
                templateId,
                planJson,
                "{\"agents\":[]}",
                "{}",
                AgentTeamRun.STATUS_RUNNING,
                null,
                baselineId,
                null,
                null,
                false,
                0L,
                LocalDateTime.now(),
                null,
                null,
                null);
    }
}
