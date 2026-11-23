package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.team.TeamPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link TeamPlan} 序列化为项目经理的 {@code tasks.json}（P0-4）。
 *
 * <p>任务级字段与产物库对齐：id/name/role/agent_id/depends_on/outputs/acceptance；
 * 依赖来自计划连线，START 不计入依赖。产物与验收缺省为空数组，由上游补齐。
 */
public final class DevTaskList {

    private DevTaskList() {
    }

    public static String toJson(TeamPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("plan_id", plan.planId());
        root.put("task_type", plan.taskType());
        root.put("template_id", plan.templateId());
        root.put("tasks", tasks(plan));
        root.put("rationale", plan.rationale());
        root.put("warnings", plan.warnings());
        return JsonSupport.toJson(root);
    }

    private static List<Map<String, Object>> tasks(TeamPlan plan) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TeamPlan.PlanNode node : plan.agentNodes()) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", node.id());
            task.put("name", node.name());
            task.put("role", node.role());
            task.put("agent_id", node.agentId());
            task.put("depends_on", dependencies(plan, node.id()));
            task.put("outputs", node.outputs());
            task.put("acceptance", node.acceptance());
            tasks.add(task);
        }
        return tasks;
    }

    static List<String> dependencies(TeamPlan plan, String nodeId) {
        List<String> dependencies = new ArrayList<>();
        for (TeamPlan.PlanEdge edge : plan.edges()) {
            if (!edge.target().equals(nodeId)) {
                continue;
            }
            TeamPlan.PlanNode source = plan.node(edge.source());
            if (source != null
                    && FlowNode.TYPE_AGENT.equals(source.type())
                    && !dependencies.contains(source.id())) {
                dependencies.add(source.id());
            }
        }
        return List.copyOf(dependencies);
    }
}
