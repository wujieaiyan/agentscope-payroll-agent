package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.team.TeamPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link TeamPlan} 的 agent 节点展开为任务面板行（P0-7）。
 *
 * <p>任务 id、负责人、依赖、产物与验收与 {@code tasks.json} 同源；优先级取节点的
 * 声明顺序（越靠前越高），状态初始为待开始，由分发逻辑按依赖推进。
 *
 * <p>P1-7：产出补丁的角色节点自动追加一条依赖它的代码审查任务，计划未显式编排审查时
 * 也能保证每份补丁都有逐文件结论；计划已含审查节点时不重复追加。
 */
public final class DevTaskPlanner {

    /** 自动生成的审查任务 id 前缀。 */
    public static final String REVIEW_PREFIX = "review-";

    private DevTaskPlanner() {}

    public static List<DevTask> plan(TeamPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        List<TeamPlan.PlanNode> nodes = plan.agentNodes();
        List<DevTask> tasks = new ArrayList<>(nodes.size());
        int priority = nodes.size();
        for (TeamPlan.PlanNode node : nodes) {
            tasks.add(
                    DevTask.pending(
                            plan.planId(),
                            node.id(),
                            defaultTitle(node),
                            node.role(),
                            node.agentId(),
                            priority--,
                            DevTaskList.dependencies(plan, node.id()),
                            node.outputs(),
                            node.acceptance()));
        }
        List<DevTask> withReviews = new ArrayList<>(tasks);
        for (DevTask task : tasks) {
            if (DevArtifactType.patchForRole(task.role()).isEmpty() || hasReview(task, tasks)) {
                continue;
            }
            withReviews.add(reviewTask(task));
        }
        return List.copyOf(withReviews);
    }

    private static boolean hasReview(DevTask task, List<DevTask> tasks) {
        return tasks.stream()
                .anyMatch(
                        candidate ->
                                DevPathPolicy.ROLE_REVIEWER.equalsIgnoreCase(candidate.role())
                                        && candidate.dependsOn().contains(task.taskId()));
    }

    private static DevTask reviewTask(DevTask source) {
        return DevTask.pending(
                source.planId(),
                REVIEW_PREFIX + source.taskId(),
                "代码审查：" + source.title(),
                DevPathPolicy.ROLE_REVIEWER,
                "",
                source.priority(),
                List.of(source.taskId()),
                List.of(DevArtifactType.REVIEW_REPORT.fileName()),
                List.of(
                        "逐文件结论：通过/建议/阻断",
                        "阻断与建议附 文件:行号 证据",
                        "存在阻断项时打回开发并重新审查"));
    }

    private static String defaultTitle(TeamPlan.PlanNode node) {
        if (node.name() != null && !node.name().isBlank()) {
            return node.name();
        }
        return node.role();
    }
}
