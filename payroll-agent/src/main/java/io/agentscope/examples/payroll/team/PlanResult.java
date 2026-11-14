package io.agentscope.examples.payroll.team;

import java.util.List;

/** 计划生成结果：成功时携带可执行计划，失败时携带回退原因与结构化错误。 */
public record PlanResult(TeamPlan plan, String fallbackReason, List<PlanError> errors) {

    public PlanResult {
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean ok() {
        return plan != null;
    }

    public static PlanResult ok(TeamPlan plan) {
        return new PlanResult(plan, "", List.of());
    }

    public static PlanResult fallback(String reason, List<PlanError> errors) {
        return new PlanResult(null, reason, errors);
    }
}
