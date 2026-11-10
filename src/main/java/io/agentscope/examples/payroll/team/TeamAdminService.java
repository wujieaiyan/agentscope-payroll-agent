package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理台团队页签的只读视图：模式配置、近期运行、运行详情与回退链路。 */
public final class TeamAdminService {

    private static final int DEFAULT_LIMIT = 100;

    private final TeamSettings settings;
    private final H2AgentTeamStore teamStore;

    public TeamAdminService(TeamSettings settings, H2AgentTeamStore teamStore) {
        this.settings = settings == null ? new TeamSettings(TeamMode.OFF, 0, 0, 0D) : settings;
        this.teamStore = teamStore;
    }

    public ConfigView config() {
        return new ConfigView(
                settings.mode().name(),
                settings.enabled(),
                settings.mode() == TeamMode.SHADOW && settings.shadowSampleRate() > 0D,
                settings.mode() == TeamMode.ACTIVE,
                settings.activeUserIds().isEmpty(),
                List.copyOf(settings.activeUserIds()),
                settings.activeSessionIds().isEmpty(),
                List.copyOf(settings.activeSessionIds()),
                settings.retentionDays(),
                settings.statsWindowDays(),
                settings.shadowSampleRate(),
                DEFAULT_LIMIT);
    }

    /** days<=0 使用统计窗口；mode/status 为空表示不过滤。 */
    public RunsView runs(int days, String mode, String status, int limit) {
        int windowDays = days <= 0 ? Math.max(1, settings.statsWindowDays()) : days;
        int cappedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        List<AgentTeamRun> rows =
                teamStore.recentRuns(since, normalize(mode), normalize(status), cappedLimit);
        List<RunRow> items = new ArrayList<>(rows.size());
        Map<String, Long> byMode = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (AgentTeamRun run : rows) {
            byMode.merge(run.mode(), 1L, Long::sum);
            byStatus.merge(run.status(), 1L, Long::sum);
            items.add(row(run));
        }
        return new RunsView(windowDays, items.size(), byMode, byStatus, items);
    }

    public DetailView detail(long runId) {
        AgentTeamRun run =
                teamStore
                        .findRun(runId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "团队运行不存在：" + runId));
        return new DetailView(run, row(run), teamStore.records(runId));
    }

    private static RunRow row(AgentTeamRun run) {
        List<String> agentIds = List.of();
        int nodeCount = 0;
        try {
            TeamPlan plan = JsonSupport.fromJson(run.planJson(), TeamPlan.class);
            agentIds = List.copyOf(plan.agentIds());
            nodeCount = plan.agentNodes().size();
        } catch (RuntimeException ignored) {
            // plan_json 可能来自早期版本或为空，展示层降级为无节点信息
        }
        return new RunRow(
                run.id(),
                run.planId(),
                run.sessionId(),
                run.messageId(),
                run.userId(),
                run.mode(),
                run.taskType(),
                run.templateId(),
                run.status(),
                run.fallbackReason(),
                run.primary(),
                run.fallbackOfRunId(),
                run.baselineRunId(),
                run.totalMs(),
                run.createdAt(),
                run.closedAt(),
                agentIds,
                nodeCount);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record ConfigView(
            String mode,
            boolean enabled,
            boolean shadowEnabled,
            boolean activeEnabled,
            boolean activeAllUsers,
            List<Long> activeUserIds,
            boolean activeAllSessions,
            List<Long> activeSessionIds,
            int retentionDays,
            int statsWindowDays,
            double shadowSampleRate,
            int defaultLimit) {}

    public record RunsView(
            int days,
            int count,
            Map<String, Long> countsByMode,
            Map<String, Long> countsByStatus,
            List<RunRow> runs) {}

    public record RunRow(
            long runId,
            String planId,
            long sessionId,
            long messageId,
            long userId,
            String mode,
            String taskType,
            String templateId,
            String status,
            String fallbackReason,
            boolean primary,
            Long fallbackOfRunId,
            Long baselineRunId,
            long totalMs,
            LocalDateTime createdAt,
            LocalDateTime closedAt,
            List<String> agentIds,
            int nodeCount) {}

    public record DetailView(AgentTeamRun run, RunRow summary, List<AgentRunRecord> records) {}
}
