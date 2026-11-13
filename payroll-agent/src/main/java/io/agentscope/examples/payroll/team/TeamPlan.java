package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 动态团队计划：模板槽位 + 短名单选角结果，使用 snake_case 落库。 */
public record TeamPlan(
        String planId,
        String taskType,
        String templateId,
        List<PlanNode> nodes,
        List<PlanEdge> edges,
        Map<String, String> roleToAgent,
        String rationale,
        List<String> warnings) {

    public TeamPlan {
        planId = planId == null ? "" : planId;
        taskType = TeamIntent.normalizeTask(taskType);
        templateId = templateId == null ? "" : templateId;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        roleToAgent = roleToAgent == null ? Map.of() : Map.copyOf(roleToAgent);
        rationale = rationale == null ? "" : rationale;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public List<PlanNode> agentNodes() {
        return nodes.stream()
                .filter(node -> FlowNode.TYPE_AGENT.equalsIgnoreCase(node.type()))
                .toList();
    }

    public PlanNode agentNode(TeamRole role) {
        return agentNodes().stream()
                .filter(node -> role.name().equalsIgnoreCase(node.role()))
                .findFirst()
                .orElse(null);
    }

    public PlanNode node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    public List<PlanEdge> edgesFrom(String sourceId) {
        return edges.stream().filter(edge -> edge.source().equals(sourceId)).toList();
    }

    public Set<String> agentIds() {
        return agentNodes().stream()
                .map(PlanNode::agentId)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public record PlanNode(
            String id,
            String type,
            String role,
            String agentId,
            String name,
            String condition,
            String conditionValue) {

        public PlanNode {
            id = id == null ? "" : id;
            type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            role = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            agentId = agentId == null ? "" : agentId;
            name = name == null ? "" : name;
            condition = condition == null ? "" : condition.trim().toUpperCase(Locale.ROOT);
            conditionValue = conditionValue == null ? "" : conditionValue;
        }

        public static PlanNode start(String id) {
            return new PlanNode(id, FlowNode.TYPE_START, "", "", "开始", "", "");
        }

        public static PlanNode end(String id) {
            return new PlanNode(id, FlowNode.TYPE_END, "", "", "结束", "", "");
        }

        public static PlanNode agent(String id, TeamRole role, String agentId, String name) {
            return new PlanNode(
                    id, FlowNode.TYPE_AGENT, role.name(), agentId, name, "", "");
        }
    }

    public record PlanEdge(String id, String source, String target, String sourceHandle) {

        public PlanEdge {
            id = id == null ? "" : id;
            source = source == null ? "" : source;
            target = target == null ? "" : target;
            sourceHandle =
                    sourceHandle == null || sourceHandle.isBlank()
                            ? FlowEdge.HANDLE_TRUE
                            : sourceHandle.trim().toUpperCase(Locale.ROOT);
        }

        public boolean trueBranch() {
            return FlowEdge.HANDLE_TRUE.equals(sourceHandle);
        }
    }

    /** 从模板实例化：槽位顺序生成 n1..n5，展示名带圈号。 */
    public static TeamPlan instantiate(
            String planId,
            String taskType,
            TeamTemplate template,
            Map<TeamRole, String> bindings,
            String rationale,
            List<String> warnings) {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(PlanNode.start("start"));
        Map<TeamRole, String> roleToNode = new LinkedHashMap<>();
        Map<String, String> roleToAgent = new LinkedHashMap<>();
        int index = 1;
        for (TeamRole role : template.slots()) {
            String nodeId = "n" + index;
            String agentId = bindings.getOrDefault(role, "");
            roleToNode.put(role, nodeId);
            if (!agentId.isBlank()) {
                roleToAgent.put(role.name(), agentId);
            }
            nodes.add(
                    PlanNode.agent(
                            nodeId,
                            role,
                            agentId,
                            circled(index) + " " + role.label()));
            index++;
        }
        nodes.add(PlanNode.end("end"));
        List<PlanEdge> edges = new ArrayList<>();
        int edgeIndex = 1;
        for (TeamTemplate.Link link : template.links()) {
            String source = nodeIdOf(link.source(), roleToNode);
            String target = nodeIdOf(link.target(), roleToNode);
            if (source == null || target == null) {
                continue;
            }
            edges.add(
                    new PlanEdge(
                            "e" + edgeIndex++,
                            source,
                            target,
                            link.sourceHandle()));
        }
        return new TeamPlan(
                planId,
                taskType,
                template.id(),
                nodes,
                edges,
                roleToAgent,
                rationale,
                warnings);
    }

    private static String nodeIdOf(String value, Map<TeamRole, String> roleToNode) {
        if ("start".equalsIgnoreCase(value)) {
            return "start";
        }
        if ("end".equalsIgnoreCase(value)) {
            return "end";
        }
        try {
            return roleToNode.get(TeamRole.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String circled(int index) {
        String[] values = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        return index >= 1 && index <= values.length ? values[index - 1] : String.valueOf(index);
    }
}
