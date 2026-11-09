package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.service.AgentStatsService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamCandidateSelectorTest {

    @Test
    @DisplayName("每个角色最多保留 3 个候选，按统计得分排序")
    void ranksCandidatesByStatsAndCapsToTopThree() {
        PayrollDatabase database = PayrollDatabase.inMemory("selector-" + System.nanoTime());
        H2AgentTeamStore teamStore = new H2AgentTeamStore(database);
        H2ChatStore chatStore = new H2ChatStore(database);
        H2ChatFeedbackStore feedbackStore = new H2ChatFeedbackStore(database);
        List<AgentDefinition> catalog =
                List.of(
                        agent("calc-a"),
                        agent("calc-b"),
                        agent("calc-c"),
                        agent("calc-d"));
        ChatSession session = chatStore.create(1L, "候选");
        long runId =
                teamStore.createRun(
                        AgentTeamRun.plannedStatic(session.id(), 10L, 1L, "plan-10", "{}"));
        for (int i = 0; i < 6; i++) {
            teamStore.appendRecord(record(runId, "calc-a", AgentRunRecord.STATUS_SUCCESS));
        }
        teamStore.appendRecord(record(runId, "calc-b", AgentRunRecord.STATUS_EMPTY));
        teamStore.finishRun(runId, AgentTeamRun.STATUS_SUCCESS, null, 800);

        AgentStatsService stats =
                new AgentStatsService(
                        teamStore, feedbackStore, new TeamSettings(TeamMode.STATIC, 180, 30, 0D), () -> catalog);
        TeamCandidateSelector selector =
                new TeamCandidateSelector(
                        CapabilityIndex.from(catalog), stats, new TeamSettings(TeamMode.STATIC, 180, 30, 0D));

        TeamShortlist shortlist =
                selector.shortlist(
                        TeamTestFixtures.calculationIntent(),
                        TeamTemplate.CALC_VALIDATE.id(),
                        List.of(TeamRole.CALCULATOR));

        List<AgentCandidate> candidates = shortlist.candidates(TeamRole.CALCULATOR);
        assertEquals(3, candidates.size());
        assertEquals("calc-a", candidates.get(0).agentId());
        assertFalse(candidates.get(0).coldStart());
        assertEquals(6, candidates.get(0).samples());
        assertEquals(1D, candidates.get(0).capabilityScore(), 1e-9);
        assertTrue(shortlist.scoringAvailable());
        assertTrue(shortlist.scoreJson().contains("p1-shadow-1"));
        assertFalse(
                candidates.stream().anyMatch(candidate -> "calc-b".equals(candidate.agentId())));
    }

    @Test
    @DisplayName("统计不可用时退化为角色先验，并标记冷启动")
    void fallsBackToRolePriorsWithoutStats() {
        List<AgentDefinition> catalog = List.of(agent("calc-a"), agent("calc-b"));
        TeamCandidateSelector selector =
                new TeamCandidateSelector(
                        CapabilityIndex.from(catalog), null, new TeamSettings(TeamMode.STATIC, 180, 30, 0D));

        TeamShortlist shortlist =
                selector.shortlist(
                        TeamTestFixtures.calculationIntent(),
                        TeamTemplate.CALC_VALIDATE.id(),
                        List.of(TeamRole.CALCULATOR));

        assertFalse(shortlist.scoringAvailable());
        List<AgentCandidate> candidates = shortlist.candidates(TeamRole.CALCULATOR);
        assertEquals(2, candidates.size());
        assertEquals("calc-a", candidates.get(0).agentId());
        assertEquals(78D, candidates.get(0).objectiveScore(), 1e-9);
        assertEquals(65D, candidates.get(0).feedbackScore(), 1e-9);
        assertEquals(0, candidates.get(0).samples());
        assertTrue(candidates.get(0).coldStart());
    }

    private static AgentDefinition agent(String id) {
        return TeamTestFixtures.agent(id, "payroll-calculator");
    }

    private static AgentRunRecord record(long runId, String agentId, String status) {
        return new AgentRunRecord(
                0L,
                runId,
                "n1",
                "calculator",
                agentId,
                TeamRole.CALCULATOR.name(),
                status,
                2,
                0,
                true,
                300,
                900,
                List.of(),
                null,
                null,
                null,
                LocalDateTime.now());
    }
}
