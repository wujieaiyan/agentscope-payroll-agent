package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentStats;
import io.agentscope.examples.payroll.domain.AgentStatsSnapshot;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentStatsServiceTest {

    private static final AgentDefinition NEW_AGENT =
            new AgentDefinition(
                    "new-agent",
                    "新智能体",
                    "🤖",
                    null,
                    List.of(),
                    List.of(),
                    AgentDefinition.VISIBILITY_PUBLIC,
                    AgentDefinition.STATUS_ONLINE,
                    "admin",
                    null,
                    null);

    private H2AgentTeamStore teamStore;
    private H2ChatFeedbackStore feedbackStore;
    private AgentStatsService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("stats-" + System.nanoTime());
        teamStore = new H2AgentTeamStore(database);
        feedbackStore = new H2ChatFeedbackStore(database);
        service =
                new AgentStatsService(
                        teamStore,
                        feedbackStore,
                        new TeamSettings(TeamMode.STATIC, 180, 30, 0.15),
                        () -> List.of(NEW_AGENT));
    }

    @Test
    @DisplayName("零样本不除零，直接落在角色先验与自定义先验上")
    void zeroSamplesFallBackToPrior() {
        AgentStatsSnapshot snapshot = service.snapshot(30);

        assertEquals(1, snapshot.agents().size());
        AgentStats stats = snapshot.agents().get(0);
        assertEquals("new-agent", stats.agentId());
        assertEquals("新智能体", stats.name());
        assertEquals(0, stats.samples());
        assertTrue(stats.coldStart());
        assertEquals(0D, stats.objectiveScore());
        assertEquals(65D, stats.finalScore());
        assertEquals(75D, stats.feedbackScore());
        assertEquals(0.0D, stats.successRate());
        assertFalse(Double.isNaN(stats.stabilityScore()));
        assertFalse(Double.isNaN(stats.latencyScore()));
    }

    @Test
    @DisplayName("节点成功率走 Wilson 下界，反馈只计入主运行真正执行的节点")
    void aggregatesWilsonAndFeedbackAttribution() {
        long runId = primaryRun(1L, 10L, 7L);
        teamStore.appendRecord(
                record(runId, "calculator", "PayrollCalculatorAgent", "CALCULATOR", "SUCCESS", 2, 0,
                        null, 120, 5_000, LocalDateTime.now()));
        teamStore.appendRecord(
                record(runId, "validator", "PayrollValidatorAgent", "VALIDATOR", "SUCCESS", 1, 0,
                        true, 80, 8_000, LocalDateTime.now()));
        teamStore.appendRecord(
                record(runId, "accountant", "PayrollAccountantAgent", "ACCOUNTANT", "SKIPPED", 0, 0,
                        null, 0, 0, LocalDateTime.now()));

        long shadowRun = teamStore.createRun(
                AgentTeamRun.plannedStatic(1L, 10L, 7L, "shadow", "{}"));
        teamStore.appendRecord(
                record(shadowRun, "validator", "AnotherValidator", "VALIDATOR", "SUCCESS", 1, 0,
                        true, 80, 8_000, LocalDateTime.now()));

        feedbackStore.upsert(10L, 1L, 7L, "DOWN", List.of("WRONG_AMOUNT", "SLOW"), null);

        AgentStatsSnapshot snapshot = service.snapshot(30);
        AgentStats calculator = find(snapshot, "PayrollCalculatorAgent");
        AgentStats validator = find(snapshot, "PayrollValidatorAgent");

        assertEquals(1, calculator.samples());
        assertTrue(calculator.successLowerBound() > 0D && calculator.successLowerBound() < 1D);
        assertEquals(0D, calculator.validationLowerBound());
        assertEquals(5_000, calculator.p50LatencyMs());
        assertEquals(20_000, calculator.latencyTargetMs());
        assertEquals(4D, calculator.downWeight());
        assertEquals(37.5D, calculator.feedbackScore());
        assertTrue(calculator.objectiveScore() > 0D && calculator.objectiveScore() <= 100D);
        assertTrue(calculator.finalScore() > 0D && calculator.finalScore() <= 100D);

        assertEquals(1, validator.samples());
        assertTrue(validator.validationLowerBound() > 0D);
        // 反馈针对整条回答，主运行中真正执行的节点共同承担差评权重。
        assertEquals(4D, validator.downWeight());

        AgentStats shadowValidator = find(snapshot, "AnotherValidator");
        assertEquals(0D, shadowValidator.downWeight());
        assertFalse(
                snapshot.agents().stream().anyMatch(a -> "PayrollAccountantAgent".equals(a.agentId())));
    }

    @Test
    @DisplayName("历史 AGENT 角色按 stage_key 归一，评分使用对应角色先验")
    void normalizesLegacyAgentRole() {
        long runId = primaryRun(3L, 30L, 7L);
        teamStore.appendRecord(
                record(runId, "calculator", "PayrollCalculatorAgent", "AGENT", "SUCCESS", 1, 0,
                        null, 120, 5_000, LocalDateTime.now()));

        AgentStats stats = find(service.snapshot(30), "PayrollCalculatorAgent");
        assertEquals("CALCULATOR", stats.role());
        assertEquals(78D, stats.priorScore());
        assertEquals(20_000, stats.latencyTargetMs());
    }

    @Test
    @DisplayName("评分快照缓存 5 分钟，失效后可重算")
    void cachesSnapshotUntilInvalidated() {
        AgentStatsSnapshot first = service.snapshot(30);
        assertFalse(first.cached());

        AgentStatsSnapshot second = service.snapshot(30);
        assertTrue(second.cached());

        service.invalidate();
        assertFalse(service.snapshot(30).cached());
    }

    @Test
    @DisplayName("Wilson 下界避免一次成功拿满分，时延与 P50 计算稳定")
    void computesStatisticalHelpers() {
        double oneOfOne = AgentStatsService.wilsonLowerBound(1D, 1D, AgentStatsService.Z95);
        double zeroTrials = AgentStatsService.wilsonLowerBound(0D, 0D, AgentStatsService.Z95);
        double fiftyOfHundred =
                AgentStatsService.wilsonLowerBound(50D, 100D, AgentStatsService.Z95);
        assertTrue(oneOfOne > 0D && oneOfOne < 0.3D);
        assertEquals(0D, zeroTrials);
        assertTrue(fiftyOfHundred > 0.4D && fiftyOfHundred < 0.5D);

        LocalDateTime now = LocalDateTime.now();
        double fresh = AgentStatsService.timeWeight(now, now);
        double monthOld = AgentStatsService.timeWeight(now.minusDays(28), now);
        assertEquals(1D, fresh, 1e-9);
        assertTrue(monthOld < 0.85D);

        long p50 =
                AgentStatsService.weightedP50(
                        List.of(
                                new AgentStatsService.LatencySample(100, 1D),
                                new AgentStatsService.LatencySample(200, 1D),
                                new AgentStatsService.LatencySample(9_000, 1D)));
        assertEquals(200, p50);
    }

    private AgentStats find(AgentStatsSnapshot snapshot, String agentId) {
        return snapshot.agents().stream()
                .filter(a -> agentId.equals(a.agentId()))
                .findFirst()
                .orElseThrow();
    }

    private long primaryRun(long sessionId, long messageId, long userId) {
        long runId =
                teamStore.createRun(
                        AgentTeamRun.plannedStatic(
                                sessionId, messageId, userId, "plan-" + messageId, "{}"));
        teamStore.finishRun(runId, AgentTeamRun.STATUS_SUCCESS, null, 1_000);
        teamStore.markPrimary(runId);
        return runId;
    }

    private static AgentRunRecord record(
            long runId,
            String nodeId,
            String agentId,
            String role,
            String status,
            int toolCalls,
            int toolErrors,
            Boolean validationPassed,
            int outputChars,
            long durationMs,
            LocalDateTime createdAt) {
        return new AgentRunRecord(
                0L,
                runId,
                nodeId,
                nodeId,
                agentId,
                role,
                status,
                toolCalls,
                toolErrors,
                validationPassed,
                outputChars,
                durationMs,
                List.of(),
                null,
                null,
                null,
                createdAt);
    }
}
