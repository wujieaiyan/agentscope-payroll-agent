package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 影子对比报告：计划率、成功率、回退分层、模板/角色分布与相对静态基线的差异。 */
public final class ShadowReportService {

    private static final Logger log = LoggerFactory.getLogger(ShadowReportService.class);
    private static final int TOP_AGENTS = 10;

    private final H2AgentTeamStore teamStore;
    private final TeamSettings settings;

    public ShadowReportService(H2AgentTeamStore teamStore, TeamSettings settings) {
        this.teamStore = teamStore;
        this.settings = settings == null ? new TeamSettings(TeamMode.OFF, 0, 0, 0D) : settings;
    }

    public ShadowReport report(int days) {
        int window = days <= 0 ? settings.statsWindowDays() : Math.min(365, Math.max(1, days));
        List<AgentTeamRun> runs =
                teamStore.shadowRunsSince(LocalDateTime.now().minusDays(window));
        int total = runs.size();
        int planned = 0;
        int success = 0;
        int fallback = 0;
        int other = 0;
        long durationSum = 0L;
        Map<String, Integer> fallbacks = new LinkedHashMap<>();
        Map<String, Integer> templates = new LinkedHashMap<>();
        Map<String, Integer> tasks = new LinkedHashMap<>();
        Map<String, Integer> agents = new LinkedHashMap<>();
        for (AgentTeamRun run : runs) {
            if (planned(run)) {
                planned++;
                durationSum += Math.max(0L, run.totalMs());
                count(templates, run.templateId());
                parseAgents(run.planJson(), agents);
            }
            if (AgentTeamRun.STATUS_SUCCESS.equals(run.status())) {
                success++;
            } else if (AgentTeamRun.STATUS_FALLBACK.equals(run.status())) {
                fallback++;
                count(fallbacks, level(run.fallbackReason()));
            } else {
                other++;
            }
            count(tasks, run.taskType());
        }
        long shadowAvg = planned == 0 ? 0L : durationSum / planned;

        List<Long> baselineIds =
                runs.stream()
                        .map(AgentTeamRun::baselineRunId)
                        .filter(id -> id != null && id > 0)
                        .distinct()
                        .toList();
        List<AgentTeamRun> baselines = teamStore.findRuns(baselineIds);
        int baselineSuccess = 0;
        long baselineDuration = 0L;
        for (AgentTeamRun baseline : baselines) {
            if (AgentTeamRun.STATUS_SUCCESS.equals(baseline.status())) {
                baselineSuccess++;
            }
            baselineDuration += Math.max(0L, baseline.totalMs());
        }
        long baselineAvg =
                baselines.isEmpty() ? 0L : baselineDuration / baselines.size();
        double baselineRate =
                baselines.isEmpty() ? 0D : (double) baselineSuccess / baselines.size();
        double shadowRate = total == 0 ? 0D : (double) success / total;
        return new ShadowReport(
                window,
                settings.shadowSampleRate(),
                total,
                planned,
                success,
                fallback,
                other,
                rate(success, total),
                rate(fallback, total),
                planned == 0 ? 0D : (double) planned / total,
                shadowAvg,
                baselines.size(),
                baselineRate,
                baselineAvg,
                shadowRate - baselineRate,
                baselines.isEmpty() ? 0L : shadowAvg - baselineAvg,
                sorted(fallbacks, Integer.MAX_VALUE),
                sorted(templates, Integer.MAX_VALUE),
                sorted(tasks, Integer.MAX_VALUE),
                sorted(agents, TOP_AGENTS));
    }

    private static boolean planned(AgentTeamRun run) {
        String plan = run.planJson();
        return plan != null && plan.contains("\"nodes\"");
    }

    private static String level(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String value = reason.trim();
        if (value.length() >= 2
                && (value.charAt(0) == 'L' || value.charAt(0) == 'l')
                && Character.isDigit(value.charAt(1))) {
            return value.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        }
        return "UNKNOWN";
    }

    private static void parseAgents(String planJson, Map<String, Integer> agents) {
        if (planJson == null || planJson.isBlank() || !planJson.contains("\"nodes\"")) {
            return;
        }
        try {
            TeamPlan plan = JsonSupport.fromJson(planJson, TeamPlan.class);
            for (TeamPlan.PlanNode node : plan.agentNodes()) {
                count(agents, node.role() + ":" + node.agentId());
            }
        } catch (RuntimeException e) {
            log.debug("[动态团队] 影子报告无法解析计划：{}", e.getMessage());
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        counts.merge(key.trim(), 1, Integer::sum);
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0D : (double) part / total;
    }

    private static Map<String, Integer> sorted(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(
                Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue())
                        .reversed()
                        .thenComparing(Map.Entry::getKey));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (sorted.size() >= limit) {
                break;
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(sorted);
    }

    /**
     * 影子报告数据；率为 0-1，耗时为毫秒，delta 为影子减基线。
     */
    public record ShadowReport(
            int windowDays,
            double sampleRate,
            int total,
            int planned,
            int success,
            int fallback,
            int other,
            double successRate,
            double fallbackRate,
            double planRate,
            long avgDurationMs,
            int baselineSamples,
            double baselineSuccessRate,
            long baselineAvgDurationMs,
            double deltaSuccessRate,
            long deltaAvgDurationMs,
            Map<String, Integer> fallbacks,
            Map<String, Integer> templates,
            Map<String, Integer> tasks,
            Map<String, Integer> agents) {
    }
}
