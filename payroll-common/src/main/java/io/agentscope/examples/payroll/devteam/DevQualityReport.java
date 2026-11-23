package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 研发质量报告（P2-3）：按计划聚合任务、补丁、审查与交付结果，给出可解释的百分制评分。
 *
 * <p>评分从 100 分起扣：失败/阻塞任务、重试、审查阻断、缺失审查、未通过 G2、交付驳回
 * 分别扣分并在 {@link #deductions()} 中列明；评分只反映流程证据，不评价需求本身。
 */
public record DevQualityReport(
        String planId,
        int tasks,
        int done,
        int failed,
        int blocked,
        int retries,
        int patchCount,
        Map<String, Integer> patchesByType,
        int reviewReports,
        int reviewBlocking,
        int reviewSuggestions,
        String approvalStatus,
        String deliveryStatus,
        String deliveryCommit,
        List<String> deductions,
        int score,
        String grade,
        LocalDateTime generatedAt) {

    public static final int GRADE_PASS = 85;
    public static final int GRADE_WATCH = 60;

    public DevQualityReport {
        planId = planId == null ? "" : planId.trim();
        patchesByType = patchesByType == null ? Map.of() : Map.copyOf(patchesByType);
        approvalStatus = approvalStatus == null || approvalStatus.isBlank() ? "none" : approvalStatus;
        deliveryStatus = deliveryStatus == null || deliveryStatus.isBlank() ? "none" : deliveryStatus;
        deliveryCommit = deliveryCommit == null ? "" : deliveryCommit.trim();
        deductions = deductions == null ? List.of() : List.copyOf(deductions);
        score = Math.max(0, Math.min(100, score));
        grade = grade == null ? "" : grade.trim();
    }

    public String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("# 研发质量报告：").append(planId).append("\n\n");
        out.append("- 评分：").append(score).append("/100（").append(grade).append("）\n");
        out.append("- 生成时间：").append(generatedAt).append("\n");
        out.append("- 交付状态：").append(deliveryStatus);
        if (!deliveryCommit.isEmpty()) {
            out.append("（commit ").append(shortCommit()).append("）");
        }
        out.append("\n\n");

        out.append("## 任务\n\n");
        out.append("| 指标 | 数值 |\n| --- | --- |\n");
        out.append("| 任务总数 | ").append(tasks).append(" |\n");
        out.append("| 完成 | ").append(done).append(" |\n");
        out.append("| 失败 | ").append(failed).append(" |\n");
        out.append("| 阻塞 | ").append(blocked).append(" |\n");
        out.append("| 重试次数 | ").append(retries).append(" |\n\n");

        out.append("## 补丁与审查\n\n");
        out.append("| 指标 | 数值 |\n| --- | --- |\n");
        out.append("| 补丁数 | ").append(patchCount).append(" |\n");
        out.append("| 审查报告 | ").append(reviewReports).append(" |\n");
        out.append("| 审查阻断 | ").append(reviewBlocking).append(" |\n");
        out.append("| 审查建议 | ").append(reviewSuggestions).append(" |\n\n");
        if (!patchesByType.isEmpty()) {
            out.append("补丁分布：");
            patchesByType.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append(entry.getKey()).append('=')
                            .append(entry.getValue()).append(' '));
            out.append("\n\n");
        }

        out.append("## 审批\n\n");
        out.append("- 最近批次：").append(approvalStatus).append("\n\n");

        out.append("## 扣分项\n\n");
        if (deductions.isEmpty()) {
            out.append("- 无\n");
        } else {
            for (String deduction : deductions) {
                out.append("- ").append(deduction).append('\n');
            }
        }
        return out.toString();
    }

    private String shortCommit() {
        return deliveryCommit.length() <= 8 ? deliveryCommit : deliveryCommit.substring(0, 8);
    }
}
