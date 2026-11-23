package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;

/**
 * 回归用例（P2-3）：一次已人工通过 G3 的交付，连同当时的质量评分作为回归基线。
 *
 * <p>回归集直接取自已批准的交付记录，不复制代码内容；后续计划可与之对比评分与
 * 审查阻断数，发现团队效果回退。
 */
public record DevRegressionCase(
        String planId,
        String deliveryId,
        String branch,
        String commitId,
        int patchCount,
        int score,
        String grade,
        LocalDateTime deliveredAt) {

    public DevRegressionCase {
        planId = planId == null ? "" : planId.trim();
        deliveryId = deliveryId == null ? "" : deliveryId.trim();
        branch = branch == null ? "" : branch.trim();
        commitId = commitId == null ? "" : commitId.trim();
        grade = grade == null ? "" : grade.trim();
    }
}
