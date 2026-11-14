package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentStats;
import io.agentscope.examples.payroll.domain.AgentStatsSnapshot;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.team.TeamSettings;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 智能体评分聚合：Wilson 下界、时延、稳定性、反馈平滑与冷启动收缩。
 *
 * <p>公式见规格 §7.3–7.6；结果缓存 5 分钟，避免管理页轮询反复扫描 H2。
 */
public final class AgentStatsService {

    static final double Z95 = 1.959963984540054D;
    static final double DECAY_PER_WEEK = 0.95D;
    static final int MAX_RECORDS_PER_AGENT = 200;
    static final int COLD_START_SAMPLES = 5;
    static final double SHRINK_K = 20D;
    static final double FEEDBACK_ALPHA = 3D;
    static final double FEEDBACK_BETA = 1D;
    static final double OBJECTIVE_WEIGHT = 0.6D;
    static final double FEEDBACK_WEIGHT = 0.4D;
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final double DEFAULT_PRIOR = 65D;
    private static final long DEFAULT_LATENCY_TARGET_MS = 15_000L;
    private static final Map<String, Long> LATENCY_TARGETS =
            Map.of(
                    "CALCULATOR", 20_000L,
                    "VALIDATOR", 15_000L,
                    "ACCOUNTANT", 20_000L,
                    "QUERY", 8_000L);
    private static final Map<String, Double> ROLE_PRIORS =
            Map.of(
                    "CALCULATOR", 78D,
                    "VALIDATOR", 80D,
                    "ACCOUNTANT", 75D,
                    "QUERY", 70D,
                    "KNOWLEDGE", 70D);
    private static final Map<String, Double> TAG_WEIGHTS =
            Map.of(
                    "WRONG_AMOUNT", 3D,
                    "INCOMPLETE", 2D,
                    "SLOW", 1D,
                    "OFF_TOPIC", 1D,
                    "OTHER", 1D);

    private final H2AgentTeamStore teamStore;
    private final H2ChatFeedbackStore feedbackStore;
    private final TeamSettings settings;
    private final Supplier<List<AgentDefinition>> agentDefinitions;
    private final Map<Integer, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public AgentStatsService(
            H2AgentTeamStore teamStore,
            H2ChatFeedbackStore feedbackStore,
            TeamSettings settings,
            Supplier<List<AgentDefinition>> agentDefinitions) {
        this.teamStore = teamStore;
        this.feedbackStore = feedbackStore;
        this.settings = settings;
        this.agentDefinitions = agentDefinitions == null ? List::of : agentDefinitions;
    }

    public AgentStatsSnapshot snapshot(int days) {
        int window = normalizeWindow(days);
        long now = System.currentTimeMillis();
        CachedSnapshot cached = cache.get(window);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.snapshot().asCached();
        }
        synchronized (cache) {
            cached = cache.get(window);
            if (cached != null && cached.expiresAtMillis() > now) {
                return cached.snapshot().asCached();
            }
            AgentStatsSnapshot fresh = compute(window);
            cache.put(window, new CachedSnapshot(fresh, now + CACHE_TTL.toMillis()));
            return fresh;
        }
    }

    public void invalidate() {
        cache.clear();
    }

    int normalizeWindow(int days) {
        if (days <= 0) {
            return Math.max(1, settings.statsWindowDays());
        }
        return Math.min(365, Math.max(1, days));
    }

    private AgentStatsSnapshot compute(int windowDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(windowDays);
        List<AgentRunRecord> records = teamStore.recordsSince(since);
        List<ChatFeedback> feedbacks = feedbackStore.listSince(since);

        Map<String, List<AgentRunRecord>> recordsByAgent = new LinkedHashMap<>();
        for (AgentRunRecord record : records) {
            if (AgentRunRecord.STATUS_SKIPPED.equals(record.status())
                    || record.agentId() == null
                    || record.agentId().isBlank()) {
                continue;
            }
            recordsByAgent.computeIfAbsent(record.agentId(), key -> new ArrayList<>()).add(record);
        }

        Map<String, FeedbackAggregate> feedbackByAgent = new HashMap<>();
        if (!feedbacks.isEmpty()) {
            Map<Long, Set<String>> executed =
                    teamStore.primaryExecutedAgents(
                            feedbacks.stream().map(ChatFeedback::messageId).toList());
            for (ChatFeedback feedback : feedbacks) {
                Set<String> agentIds = executed.get(feedback.messageId());
                if (agentIds == null) {
                    continue;
                }
                double downWeight = downWeight(feedback);
                for (String agentId : agentIds) {
                    FeedbackAggregate aggregate =
                            feedbackByAgent.computeIfAbsent(agentId, key -> new FeedbackAggregate());
                    if (ChatFeedback.RATING_UP.equals(feedback.rating())) {
                        aggregate.up++;
                    } else if (ChatFeedback.RATING_DOWN.equals(feedback.rating())) {
                        aggregate.down += downWeight;
                    }
                }
            }
        }

        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        for (AgentDefinition definition : agentDefinitions.get()) {
            definitions.put(definition.id(), definition);
        }

        Map<String, AgentStats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<AgentRunRecord>> entry : recordsByAgent.entrySet()) {
            String agentId = entry.getKey();
            List<AgentRunRecord> agentRecords = entry.getValue();
            if (agentRecords.size() > MAX_RECORDS_PER_AGENT) {
                agentRecords = agentRecords.subList(0, MAX_RECORDS_PER_AGENT);
            }
            AgentDefinition definition = definitions.get(agentId);
            String name = definition == null ? agentId : definition.name();
            AgentStats value =
                    aggregate(
                            agentId,
                            name,
                            agentRecords,
                            feedbackByAgent.getOrDefault(agentId, new FeedbackAggregate()),
                            now);
            stats.put(agentId, value);
        }
        // 零样本智能体也要出现在榜单里，分数直接落在角色先验上。
        for (AgentDefinition definition : definitions.values()) {
            stats.computeIfAbsent(
                    definition.id(),
                    id ->
                            aggregate(
                                    id,
                                    definition.name(),
                                    List.of(),
                                    feedbackByAgent.getOrDefault(id, new FeedbackAggregate()),
                                    now));
        }
        for (String agentId : feedbackByAgent.keySet()) {
            stats.computeIfAbsent(
                    agentId,
                    id -> aggregate(id, id, List.of(), feedbackByAgent.get(id), now));
        }

        List<AgentStats> ranked = new ArrayList<>(stats.values());
        ranked.sort(
                Comparator.comparingDouble(AgentStats::finalScore)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(AgentStats::samples).reversed())
                        .thenComparing(AgentStats::agentId));
        return new AgentStatsSnapshot(
                windowDays, now, false, records.size(), feedbacks.size(), ranked);
    }

    private static AgentStats aggregate(
            String agentId,
            String name,
            List<AgentRunRecord> records,
            FeedbackAggregate feedback,
            LocalDateTime now) {
        int samples = records.size();
        String role = dominantRole(records);
        double prior = priorFor(role);
        long targetMs = latencyTargetFor(role);

        double totalWeight = 0D;
        double successWeight = 0D;
        double toolTrialWeight = 0D;
        double toolOkWeight = 0D;
        double validationTrialWeight = 0D;
        double validationOkWeight = 0D;
        double timeoutWeight = 0D;
        double emptyWeight = 0D;
        List<LatencySample> latencies = new ArrayList<>(samples);
        for (AgentRunRecord record : records) {
            double weight = timeWeight(record.createdAt(), now);
            totalWeight += weight;
            if (AgentRunRecord.STATUS_SUCCESS.equals(record.status())) {
                successWeight += weight;
            }
            if (record.toolCalls() > 0) {
                toolTrialWeight += weight;
                if (record.toolErrors() == 0) {
                    toolOkWeight += weight;
                }
            }
            if (record.validationPassed() != null) {
                validationTrialWeight += weight;
                if (Boolean.TRUE.equals(record.validationPassed())) {
                    validationOkWeight += weight;
                }
            }
            if (AgentRunRecord.STATUS_TIMEOUT.equals(record.status())) {
                timeoutWeight += weight;
            }
            if (AgentRunRecord.STATUS_EMPTY.equals(record.status())
                    || (AgentRunRecord.STATUS_SUCCESS.equals(record.status())
                            && record.outputChars() == 0)) {
                emptyWeight += weight;
            }
            latencies.add(new LatencySample(record.durationMs(), weight));
        }
        if (totalWeight <= 0D) {
            totalWeight = 1D;
        }

        double successRate = samples == 0 ? 0D : successWeight / totalWeight;
        double successLb = wilsonLowerBound(successWeight, totalWeight, Z95);
        double toolOkLb = wilsonLowerBound(toolOkWeight, toolTrialWeight, Z95);
        double validationLb = wilsonLowerBound(validationOkWeight, validationTrialWeight, Z95);
        long p50 = weightedP50(latencies);
        double latencyScore = samples == 0 ? 0D : clamp((double) targetMs / Math.max(p50, 1L), 0D, 1D);
        double stability =
                samples == 0
                        ? 1D
                        : clamp(1D - timeoutWeight / totalWeight - emptyWeight / totalWeight, 0D, 1D);
        double objective =
                samples == 0
                        ? 0D
                        : 100D
                                * (0.30D * successLb
                                        + 0.20D * toolOkLb
                                        + 0.20D * validationLb
                                        + 0.15D * latencyScore
                                        + 0.15D * stability);

        double feedbackScore =
                100D
                        * (feedback.up + FEEDBACK_ALPHA)
                        / (feedback.up + feedback.down + FEEDBACK_ALPHA + FEEDBACK_BETA);
        double empirical = OBJECTIVE_WEIGHT * objective + FEEDBACK_WEIGHT * feedbackScore;
        double finalScore = (samples * empirical + SHRINK_K * prior) / (samples + SHRINK_K);

        LocalDateTime lastSeen = records.isEmpty() ? null : records.get(0).createdAt();
        return new AgentStats(
                agentId,
                name,
                role == null ? "" : role,
                samples,
                samples < COLD_START_SAMPLES,
                round4(successRate),
                round4(successLb),
                round4(toolOkLb),
                round4(validationLb),
                p50,
                targetMs,
                round4(latencyScore),
                round4(stability),
                round2(objective),
                feedback.up,
                round2(feedback.down),
                round2(feedbackScore),
                round2(empirical),
                round2(prior),
                round2(finalScore),
                lastSeen);
    }

    private static double downWeight(ChatFeedback feedback) {
        if (!ChatFeedback.RATING_DOWN.equals(feedback.rating())) {
            return 0D;
        }
        if (feedback.tags().isEmpty()) {
            return 1D;
        }
        double weight = 0D;
        for (String tag : feedback.tags()) {
            weight += TAG_WEIGHTS.getOrDefault(tag.toUpperCase(Locale.ROOT), 1D);
        }
        return Math.max(1D, weight);
    }

    private static String dominantRole(List<AgentRunRecord> records) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentRunRecord record : records) {
            String role = record.role();
            if (role == null || role.isBlank() || "AGENT".equalsIgnoreCase(role)) {
                role = roleOfStage(record.stageKey());
            }
            if (role != null) {
                counts.merge(role, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String roleOfStage(String stageKey) {
        if (stageKey == null || stageKey.isBlank()) {
            return null;
        }
        return switch (stageKey) {
            case "calculator" -> "CALCULATOR";
            case "validator" -> "VALIDATOR";
            case "accountant" -> "ACCOUNTANT";
            case "query" -> "QUERY";
            case "knowledge" -> "KNOWLEDGE";
            default -> stageKey.toUpperCase(Locale.ROOT);
        };
    }

    static double priorFor(String role) {
        if (role == null || role.isBlank()) {
            return DEFAULT_PRIOR;
        }
        return ROLE_PRIORS.getOrDefault(role.toUpperCase(Locale.ROOT), DEFAULT_PRIOR);
    }

    static long latencyTargetFor(String role) {
        if (role == null || role.isBlank()) {
            return DEFAULT_LATENCY_TARGET_MS;
        }
        return LATENCY_TARGETS.getOrDefault(role.toUpperCase(Locale.ROOT), DEFAULT_LATENCY_TARGET_MS);
    }

    static double wilsonLowerBound(double successes, double trials, double z) {
        if (trials <= 0D) {
            return 0D;
        }
        double p = clamp(successes / trials, 0D, 1D);
        double z2 = z * z;
        double denominator = 1D + z2 / trials;
        double centre = p + z2 / (2D * trials);
        double margin = z * Math.sqrt((p * (1D - p) + z2 / (4D * trials)) / trials);
        return clamp((centre - margin) / denominator, 0D, 1D);
    }

    static double timeWeight(LocalDateTime createdAt, LocalDateTime now) {
        if (createdAt == null) {
            return 1D;
        }
        double ageDays = Duration.between(createdAt, now).toMinutes() / 1440D;
        return Math.pow(DECAY_PER_WEEK, Math.max(0D, ageDays) / 7D);
    }

    static long weightedP50(List<LatencySample> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }
        List<LatencySample> sorted =
                samples.stream().sorted(Comparator.comparingLong(LatencySample::durationMs)).toList();
        double total = sorted.stream().mapToDouble(LatencySample::weight).sum();
        double cumulative = 0D;
        for (LatencySample sample : sorted) {
            cumulative += sample.weight();
            if (cumulative >= total / 2D) {
                return sample.durationMs();
            }
        }
        return sorted.get(sorted.size() - 1).durationMs();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private static double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    record LatencySample(long durationMs, double weight) {
    }

    private static final class FeedbackAggregate {
        private int up;
        private double down;
    }

    private record CachedSnapshot(AgentStatsSnapshot snapshot, long expiresAtMillis) {
    }
}
