package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 把已校验的 {@link TeamPlan} 编译为 {@link FlowDefinition}，交由现有 DAG 执行器运行。 */
public final class TeamPlanCompiler {

    private static final String CREATED_BY = "dynamic-team";
    private static final double COLUMN_WIDTH = 190D;
    private static final double ROW_Y = 120D;

    private TeamPlanCompiler() {
    }

    public static FlowDefinition compile(TeamPlan plan, String flowId) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        String id = flowId == null || flowId.isBlank() ? "dynamic-" + plan.planId() : flowId;
        List<FlowNode> nodes = new ArrayList<>();
        int column = 0;
        for (TeamPlan.PlanNode node : plan.nodes()) {
            nodes.add(
                    new FlowNode(
                            node.id(),
                            node.type(),
                            node.name(),
                            node.agentId(),
                            node.condition(),
                            node.conditionValue(),
                            column * COLUMN_WIDTH,
                            ROW_Y));
            column++;
        }
        List<FlowEdge> edges = new ArrayList<>();
        for (TeamPlan.PlanEdge edge : plan.edges()) {
            edges.add(
                    new FlowEdge(
                            edge.id(), edge.source(), edge.target(), edge.sourceHandle()));
        }
        TeamTemplate template = TeamTemplate.from(plan.templateId());
        String templateLabel = template == null ? plan.templateId() : template.label();
        LocalDateTime now = LocalDateTime.now();
        return new FlowDefinition(
                id,
                "动态团队·" + templateLabel,
                "由意图 " + plan.taskType() + " 自动生成（" + plan.templateId() + "）",
                nodes,
                edges,
                CREATED_BY,
                now,
                now);
    }
}
