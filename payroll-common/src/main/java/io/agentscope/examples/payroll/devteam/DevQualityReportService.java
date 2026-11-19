package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevDeliveryStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 研发质量评分与团队效果报表（P2-3）：只读聚合任务、补丁、审查与交付记录，
 * 不修改任何状态，也不触发模型调用。
 *
 * <p>评分口径固定且可解释（见 {@link DevQualityReport}）：失败/阻塞、重试、审查阻断、
 * 缺失审查、未通过 G2 与交付驳回分别扣分；已交付的 G3 交付自动成为回归用例，
 * 团队报表汇总各计划评分、角色执行情况与回归基线。
 */
public final class DevQualityReportService {

    private static final List<DevArtifactType> PATCH_TYPES =
            List.of(
                    DevArtifactType.PATCH_BACKEND,
                    DevArtifactType.PATCH_FRONTEND,
                    DevArtifactType.PATCH_DB,
                    DevArtifactType.PATCH_DEVOPS);

    private final DevTaskStore tasks;
    private final DevArtifactStore artifacts;
    private final DevApprovalStore approvals;
    private final DevDeliveryStore deliveries;
    private final Clock clock;

    public DevQualityReportService(
            DevTaskStore tasks,
            DevArtifactStore artifacts,
            DevApprovalStore approvals,
            DevDeliveryStore deliveries) {
        this(tasks, artifacts, approvals, deliveries, Clock.systemDefaultZone());
    }

    public DevQualityReportService(
            DevTaskStore tasks,
            DevArtifactStore artifacts,
            DevApprovalStore approvals,
            DevDeliveryStore deliveries,
            Clock clock) {
        if (tasks == null || artifacts == null || approvals == null || deliveries == null) {
            throw new IllegalArgumentException("质量评分依赖的存储不能为空");
        }
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.approvals = approvals;
        this.deliveries = deliveries;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public DevQualityReport report(String planId) {
        String plan = requirePlan(planId);
        List<DevTask> planTasks = tasks.list(plan);
        int done = 0;
        int failed = 0;
        int blocked = 0;
        int retries = 0;
        for (DevTask task : planTasks) {
            if (task.status() == DevTaskStatus.DONE) {
                done++;
            } else if (task.status() == DevTaskStatus.FAILED) {
                failed++;
            } else if (task.status() == DevTaskStatus.BLOCKED) {
                blocked++;
            }
            retries += task.attempt();
        }

        Map<String, Integer> patchesByType = new TreeMap<>();
        int patchCount = 0;
        int reviewReports = 0;
        int reviewBlocking = 0;
        int reviewSuggestions = 0;
        int missingReviews = 0;
        for (DevTask task : planTasks) {
            boolean hasPatch = false;
            for (DevArtifactType type : PATCH_TYPES) {
                if (artifacts.latest(task.taskId(), type).isPresent()) {
                    hasPatch = true;
                    patchCount++;
                    patchesByType.merge(type.id(), 1, Integer::sum);
                }
            }
            Optional<DevArtifact> review =
                    artifacts.latest(task.taskId(), DevArtifactType.REVIEW_REPORT);
            if (review.isPresent()) {
                DevReviewReport report = DevReviewReport.parse(review.get().content());
                reviewReports++;
                reviewBlocking += report.blocking().size();
                reviewSuggestions += report.count(DevReviewReport.Verdict.SUGGEST);
            } else if (hasPatch) {
                missingReviews++;
            }
        }

        String approvalStatus =
                approvals.list(plan).stream()
                        .findFirst()
                        .map(batch -> batch.status().wireName())
                        .orElse("none");
        Optional<DevDelivery> delivery = deliveries.list(plan).stream().findFirst();
        String deliveryStatus =
                delivery.map(d -> d.status().wireName()).orElse("none");
        String deliveryCommit = delivery.map(DevDelivery::commitId).orElse("");

        List<String> deductions = new ArrayList<>();
        int score = 100;
        if (failed + blocked > 0) {
            int deduct = Math.min(30, 10 * (failed + blocked));
            score -= deduct;
            deductions.add("失败/阻塞任务 " + (failed + blocked) + " 个（-" + deduct + "）");
        }
        if (retries > 0) {
            int deduct = Math.min(10, 2 * retries);
            score -= deduct;
            deductions.add("人工重试 " + retries + " 次（-" + deduct + "）");
        }
        if (reviewBlocking > 0) {
            int deduct = Math.min(20, 10 * reviewBlocking);
            score -= deduct;
            deductions.add("审查阻断 " + reviewBlocking + " 项（-" + deduct + "）");
        }
        if (missingReviews > 0) {
            int deduct = Math.min(20, 10 * missingReviews);
            score -= deduct;
            deductions.add("补丁缺少审查报告 " + missingReviews + " 份（-" + deduct + "）");
        }
        if (patchCount > 0 && !"approved".equals(approvalStatus)) {
            score -= 20;
            deductions.add("补丁未通过 G2 批量审批（-20）");
        }
        if ("rejected".equals(deliveryStatus) || "stale".equals(deliveryStatus)) {
            score -= 10;
            deductions.add("交付被驳回或补丁漂移（-10）");
        }
        if ("approved".equals(deliveryStatus)) {
            score = Math.min(100, score + 5);
        }
        score = Math.max(0, score);
        String grade = gradeOf(score);
        return new DevQualityReport(
                plan,
                planTasks.size(),
                done,
                failed,
                blocked,
                retries,
                patchCount,
                patchesByType,
                reviewReports,
                reviewBlocking,
                reviewSuggestions,
                approvalStatus,
                deliveryStatus,
                deliveryCommit,
                deductions,
                score,
                grade,
                LocalDateTime.now(clock));
    }

    /** 回归集：每个计划最近一次已通过的 G3 交付，附当时的质量评分。 */
    public List<DevRegressionCase> regressionCases(Collection<String> planIds) {
        List<DevRegressionCase> cases = new ArrayList<>();
        for (String planId : distinctPlans(planIds)) {
            Optional<DevDelivery> approved =
                    deliveries.list(planId).stream()
                            .filter(d -> d.status() == DevDeliveryStatus.APPROVED)
                            .findFirst();
            if (approved.isEmpty()) {
                continue;
            }
            DevDelivery delivery = approved.get();
            DevQualityReport report = report(planId);
            LocalDateTime at =
                    delivery.decidedAt() != null ? delivery.decidedAt() : delivery.createdAt();
            cases.add(
                    new DevRegressionCase(
                            planId,
                            delivery.deliveryId(),
                            delivery.branch(),
                            delivery.commitId(),
                            delivery.patches().size(),
                            report.score(),
                            report.grade(),
                            at));
        }
        cases.sort(
                Comparator.comparing(
                                DevRegressionCase::deliveredAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DevRegressionCase::planId));
        return List.copyOf(cases);
    }

    /** 团队效果报表：逐计划评分、角色执行情况与回归基线。 */
    public String teamReport(Collection<String> planIds) {
        List<String> plans = distinctPlans(planIds);
        List<DevQualityReport> reports = new ArrayList<>();
        for (String plan : plans) {
            reports.add(report(plan));
        }
        StringBuilder out = new StringBuilder();
        out.append("# 研发团队效果报表\n\n");
        out.append("- 生成时间：").append(LocalDateTime.now(clock)).append("\n");
        out.append("- 计划数：").append(reports.size()).append("\n");
        if (!reports.isEmpty()) {
            int total = 0;
            int passed = 0;
            for (DevQualityReport report : reports) {
                total += report.score();
                if (report.score() >= DevQualityReport.GRADE_PASS) {
                    passed++;
                }
            }
            out.append("- 平均评分：")
                    .append(Math.round((double) total / reports.size()))
                    .append("\n");
            out.append("- 达标计划：").append(passed).append('/').append(reports.size()).append("\n");
        }
        out.append('\n');

        out.append("## 计划评分\n\n");
        out.append("| 计划 | 评分 | 结论 | 任务(完成/总数) | 审查阻断 | 审批 | 交付 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (DevQualityReport report : reports) {
            out.append("| ")
                    .append(report.planId())
                    .append(" | ")
                    .append(report.score())
                    .append(" | ")
                    .append(report.grade())
                    .append(" | ")
                    .append(report.done())
                    .append('/')
                    .append(report.tasks())
                    .append(" | ")
                    .append(report.reviewBlocking())
                    .append(" | ")
                    .append(report.approvalStatus())
                    .append(" | ")
                    .append(report.deliveryStatus())
                    .append(" |\n");
        }
        out.append('\n');

        Map<String, int[]> byRole = new LinkedHashMap<>();
        for (String plan : plans) {
            for (DevTask task : tasks.list(plan)) {
                int[] stats = byRole.computeIfAbsent(task.role(), key -> new int[4]);
                stats[0]++;
                if (task.status() == DevTaskStatus.DONE) {
                    stats[1]++;
                }
                if (task.status().failure()) {
                    stats[2]++;
                }
                stats[3] += task.attempt();
            }
        }
        out.append("## 角色执行\n\n");
        out.append("| 角色 | 任务数 | 完成 | 失败/阻塞 | 重试 |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        byRole.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry ->
                                out.append("| ")
                                        .append(entry.getKey())
                                        .append(" | ")
                                        .append(entry.getValue()[0])
                                        .append(" | ")
                                        .append(entry.getValue()[1])
                                        .append(" | ")
                                        .append(entry.getValue()[2])
                                        .append(" | ")
                                        .append(entry.getValue()[3])
                                        .append(" |\n"));
        out.append('\n');

        List<DevRegressionCase> cases = regressionCases(plans);
        out.append("## 回归基线\n\n");
        if (cases.isEmpty()) {
            out.append("- 暂无已通过的交付\n");
        } else {
            out.append("| 计划 | 分支 | commit | 补丁数 | 基线评分 | 交付时间 |\n");
            out.append("| --- | --- | --- | --- | --- | --- |\n");
            for (DevRegressionCase regression : cases) {
                out.append("| ")
                        .append(regression.planId())
                        .append(" | ")
                        .append(regression.branch())
                        .append(" | ")
                        .append(shortCommit(regression.commitId()))
                        .append(" | ")
                        .append(regression.patchCount())
                        .append(" | ")
                        .append(regression.score())
                        .append(" | ")
                        .append(regression.deliveredAt())
                        .append(" |\n");
            }
        }
        return out.toString();
    }

    public static String gradeOf(int score) {
        if (score >= DevQualityReport.GRADE_PASS) {
            return "达标";
        }
        return score >= DevQualityReport.GRADE_WATCH ? "需关注" : "不达标";
    }

    private static String shortCommit(String commitId) {
        if (commitId == null || commitId.isBlank()) {
            return "";
        }
        return commitId.length() <= 8 ? commitId : commitId.substring(0, 8);
    }

    private static List<String> distinctPlans(Collection<String> planIds) {
        LinkedHashSet<String> plans = new LinkedHashSet<>();
        if (planIds != null) {
            for (String planId : planIds) {
                plans.add(requirePlan(planId));
            }
        }
        return List.copyOf(plans);
    }

    private static String requirePlan(String planId) {
        String plan = planId == null ? "" : planId.trim();
        if (plan.isEmpty()) {
            throw new PayrollException("计划 id 不能为空");
        }
        if (plan.length() > 64) {
            throw new PayrollException("计划 id 过长：" + planId);
        }
        return plan;
    }
}
