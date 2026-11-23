package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.team.TeamPlan;

/** 计划产物落库（P0-4）：项目经理把带产物/验收的 {@link TeamPlan} 写成 {@code tasks.json}。 */
public final class DevPlanArtifacts {

    public static final String OWNER = "project-manager";
    private static final String TASKS_TITLE = "任务清单（tasks.json）";

    private final DevArtifactStore store;

    public DevPlanArtifacts(DevArtifactStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为空");
        }
        this.store = store;
    }

    /**
     * 落库任务清单；{@code taskId} 为空时使用 plan id。
     *
     * @return 版本化后的产物记录，同内容重复提交返回最新版本
     */
    public DevArtifact saveTasks(String taskId, TeamPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        String id = taskId == null || taskId.isBlank() ? plan.planId() : taskId;
        return store.save(id, DevArtifactType.TASKS, OWNER, TASKS_TITLE, DevTaskList.toJson(plan), null);
    }
}
