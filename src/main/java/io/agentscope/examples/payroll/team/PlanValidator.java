package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划校验（确定性）。任何一项失败都返回结构化错误，由生成器做一次修复或回退。
 *
 * <p>校验覆盖规格 §8.3 的 8 项：DAG、节点类型/条件边、智能体可用性、强制角色与复核顺序、
 * 规模/深度/去重、写操作、角色工具边界、模板/条件版本。
 */
public final class PlanValidator {

    private static final Set<String> NODE_TYPES =
            Set.of(
                    FlowNode.TYPE_START,
                    FlowNode.TYPE_AGENT,
                    FlowNode.TYPE_CONDITION,
                    FlowNode.TYPE_END);
    private static final Set<String> CONDITIONS =
            Set.of(
                    FlowNode.CONDITION_HAS_TARGETS,
                    FlowNode.CONDITION_HAS_TOOL_ERROR,
                    FlowNode.CONDITION_OUTPUT_CONTAINS);

    private final CapabilityIndex index;

    public PlanValidator(CapabilityIndex index) {
        this.index = index;
    }

    public PlanValidation validate(TeamPlan plan, TeamIntent intent) {
        List<PlanError> errors = new ArrayList<>();
        if (plan == null) {
            return new PlanValidation(List.of(PlanError.of("EMPTY_PLAN", "计划为空")));
        }
        if (intent == null || intent.writeAction()) {
            errors.add(PlanError.of("WRITE_ACTION", "写操作不允许进入动态团队"));
        }
        TeamTemplate template = TeamTemplate.from(plan.templateId());
        if (template == null || template.fallback()) {
            errors.add(
                    PlanError.of(
                            "UNKNOWN_TEMPLATE", "未知或回退模板：" + plan.templateId()));
        }
        if (plan.nodes().isEmpty()) {
            errors.add(PlanError.of("EMPTY_PLAN", "计划没有节点"));
            return new PlanValidation(errors);
        }

        Map<String, TeamPlan.PlanNode> nodes = new LinkedHashMap<>();
        for (TeamPlan.PlanNode node : plan.nodes()) {
            if (node.id().isBlank()) {
                errors.add(PlanError.of("NODE_ID_BLANK", "节点 id 为空"));
                continue;
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                errors.add(PlanError.at("NODE_DUPLICATE", node.id(), "节点 id 重复"));
            }
        }

        TeamPlan.PlanNode start = singleNode(nodes, FlowNode.TYPE_START, "START", errors);
        TeamPlan.PlanNode end = singleNode(nodes, FlowNode.TYPE_END, "END", errors);

        List<TeamPlan.PlanNode> agentNodes = new ArrayList<>();
        Map<String, List<TeamPlan.PlanEdge>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            indegree.put(id, 0);
        });
        Set<String> edgeIds = new LinkedHashSet<>();
        for (TeamPlan.PlanEdge edge : plan.edges()) {
            if (!edgeIds.add(edge.id())) {
                errors.add(PlanError.of("EDGE_DUPLICATE", "连线 id 重复：" + edge.id()));
            }
            if (edge.source().equals(edge.target())) {
                errors.add(PlanError.at("SELF_LOOP", edge.source(), "节点不能连向自身"));
                continue;
            }
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                errors.add(
                        PlanError.of(
                                "EDGE_UNKNOWN",
                                "连线引用了不存在的节点："
                                        + edge.source()
                                        + " → "
                                        + edge.target()));
                continue;
            }
            outgoing.get(edge.source()).add(edge);
            indegree.merge(edge.target(), 1, Integer::sum);
            TeamPlan.PlanNode source = nodes.get(edge.source());
            if (FlowNode.TYPE_CONDITION.equals(source.type())
                    && !FlowEdge.HANDLE_TRUE.equals(edge.sourceHandle())
                    && !FlowEdge.HANDLE_FALSE.equals(edge.sourceHandle())) {
                errors.add(
                        PlanError.at(
                                "CONDITION_HANDLE",
                                edge.id(),
                                "条件节点出边必须标记 TRUE/FALSE"));
            }
        }

        for (TeamPlan.PlanNode node : nodes.values()) {
            if (!NODE_TYPES.contains(node.type())) {
                errors.add(
                        PlanError.at(
                                "NODE_TYPE", node.id(), "非法节点类型：" + node.type()));
                continue;
            }
            if (FlowNode.TYPE_AGENT.equals(node.type())) {
                agentNodes.add(node);
                if (node.agentId().isBlank()) {
                    errors.add(PlanError.at("AGENT_MISSING", node.id(), "智能体节点缺少 agentId"));
                }
                if (TeamRole.parse(node.role()) == null) {
                    errors.add(
                            PlanError.at(
                                    "ROLE_INVALID", node.id(), "非法角色：" + node.role()));
                }
            }
            if (FlowNode.TYPE_CONDITION.equals(node.type())) {
                if (!CONDITIONS.contains(node.condition())) {
                    errors.add(
                            PlanError.at(
                                    "CONDITION_INVALID",
                                    node.id(),
                                    "非法条件：" + node.condition()));
                }
                if (FlowNode.CONDITION_OUTPUT_CONTAINS.equals(node.condition())
                        && node.conditionValue().isBlank()) {
                    errors.add(
                            PlanError.at(
                                    "CONDITION_VALUE_MISSING",
                                    node.id(),
                                    "OUTPUT_CONTAINS 缺少比较值"));
                }
                long trueEdges =
                        outgoing.get(node.id()).stream().filter(TeamPlan.PlanEdge::trueBranch).count();
                long falseEdges =
                        outgoing.get(node.id()).stream()
                                .filter(edge -> !edge.trueBranch())
                                .count();
                if (trueEdges != 1 || falseEdges != 1) {
                    errors.add(
                            PlanError.at(
                                    "CONDITION_EDGES",
                                    node.id(),
                                    "条件节点必须各有一条 TRUE/FALSE 出边"));
                }
            }
        }

        if (start != null && indegree.getOrDefault(start.id(), 0) > 0) {
            errors.add(PlanError.at("START_INCOMING", start.id(), "START 不能有入边"));
        }
        if (end != null && !outgoing.getOrDefault(end.id(), List.of()).isEmpty()) {
            errors.add(PlanError.at("END_OUTGOING", end.id(), "END 不能有出边"));
        }

        if (agentNodes.isEmpty() || agentNodes.size() > TeamTemplate.MAX_AGENT_NODES) {
            errors.add(
                    PlanError.of(
                            "AGENT_COUNT",
                            "智能体节点数必须在 1-"
                                    + TeamTemplate.MAX_AGENT_NODES
                                    + " 之间，实际="
                                    + agentNodes.size()));
        }
        Set<String> agentIds = new LinkedHashSet<>();
        Set<TeamRole> roles = new LinkedHashSet<>();
        for (TeamPlan.PlanNode node : agentNodes) {
            if (!node.agentId().isBlank() && !agentIds.add(node.agentId())) {
                errors.add(
                        PlanError.at(
                                "DUPLICATE_AGENT", node.id(), "智能体重复：" + node.agentId()));
            }
            TeamRole role = TeamRole.parse(node.role());
            if (role != null && !roles.add(role)) {
                errors.add(
                        PlanError.at("DUPLICATE_ROLE", node.id(), "角色重复：" + role));
            }
        }

        if (template != null && !template.fallback()) {
            for (TeamRole role : template.slots()) {
                if (roles.stream().noneMatch(role::equals)) {
                    errors.add(
                            PlanError.of(
                                    "MANDATORY_ROLE_MISSING",
                                    "模板缺少强制角色：" + role.name()));
                }
            }
            for (TeamRole role : roles) {
                if (!template.slots().contains(role)) {
                    errors.add(
                            PlanError.of(
                                    "ROLE_NOT_IN_TEMPLATE",
                                    "模板 " + template.id() + " 不包含角色：" + role.name()));
                }
            }
        }
        if (intent != null && intent.needsValidation() && !roles.contains(TeamRole.VALIDATOR)) {
            errors.add(
                    PlanError.of("MISSING_VALIDATION", "涉及金额的计划必须包含 VALIDATOR"));
        }

        TeamPlan.PlanNode calculator = findAgent(agentNodes, TeamRole.CALCULATOR);
        TeamPlan.PlanNode validator = findAgent(agentNodes, TeamRole.VALIDATOR);
        if (calculator != null
                && validator != null
                && !reachable(calculator.id(), validator.id(), outgoing)) {
            errors.add(
                    PlanError.at(
                            "VALIDATOR_ORDER",
                            validator.id(),
                            "VALIDATOR 必须位于 CALCULATOR 下游"));
        }

        for (TeamPlan.PlanNode node : agentNodes) {
            validateAgent(node, plan, errors);
        }

        if (start != null) {
            validateGraph(nodes.keySet(), start, end, outgoing, indegree, errors);
        }
        return new PlanValidation(errors);
    }

    private void validateAgent(TeamPlan.PlanNode node, TeamPlan plan, List<PlanError> errors) {
        TeamRole role = TeamRole.parse(node.role());
        AgentDefinition definition = index.definition(node.agentId());
        if (definition == null) {
            errors.add(
                    PlanError.at(
                            "AGENT_UNKNOWN", node.id(), "智能体不存在：" + node.agentId()));
        } else {
            if (!AgentDefinition.STATUS_ONLINE.equals(definition.status())) {
                errors.add(
                        PlanError.at(
                                "AGENT_OFFLINE", node.id(), "智能体未上架：" + node.agentId()));
            }
            if (!AgentDefinition.VISIBILITY_PUBLIC.equals(definition.visibility())) {
                errors.add(
                        PlanError.at(
                                "AGENT_PRIVATE", node.id(), "私有智能体不能进入自动团队：" + node.agentId()));
            }
            if (!index.skillsUsable(definition)) {
                errors.add(
                        PlanError.at(
                                "SKILL_UNUSABLE",
                                node.id(),
                                "智能体技能不可解析：" + node.agentId()));
            }
            AgentCapabilityProfile profile = index.profile(node.agentId());
            if (profile == null || role == null || !role.matches(profile.capabilities())) {
                errors.add(
                        PlanError.at(
                                "ROLE_MISMATCH",
                                node.id(),
                                "智能体能力不匹配角色 " + node.role() + "：" + node.agentId()));
            } else if (role.mandatory() && !profile.canBeMandatoryRole()) {
                errors.add(
                        PlanError.at(
                                "NOT_MANDATORY",
                                node.id(),
                                "推断能力智能体不能担任强制角色：" + node.agentId()));
            }
        }
        if (role != null && !plan.roleToAgent().isEmpty()) {
            String bound = plan.roleToAgent().get(role.name());
            if (bound == null || !bound.equals(node.agentId())) {
                errors.add(
                        PlanError.at(
                                "ROLE_BINDING_MISMATCH",
                                node.id(),
                                "角色绑定与节点不一致：" + role.name()));
            }
        }
    }

    private void validateGraph(
            Set<String> nodeIds,
            TeamPlan.PlanNode start,
            TeamPlan.PlanNode end,
            Map<String, List<TeamPlan.PlanEdge>> outgoing,
            Map<String, Integer> indegree,
            List<PlanError> errors) {
        Map<String, Integer> remaining = new HashMap<>(indegree);
        Deque<String> queue = new ArrayDeque<>();
        remaining.forEach(
                (id, degree) -> {
                    if (degree == 0) {
                        queue.add(id);
                    }
                });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);
            for (TeamPlan.PlanEdge edge : outgoing.getOrDefault(current, List.of())) {
                int degree = remaining.getOrDefault(edge.target(), 0) - 1;
                remaining.put(edge.target(), degree);
                if (degree == 0) {
                    queue.add(edge.target());
                }
            }
        }
        if (order.size() != nodeIds.size()) {
            errors.add(PlanError.of("CYCLE", "计划存在环，无法构成 DAG"));
            return;
        }
        Map<String, Integer> depth = new HashMap<>();
        depth.put(start.id(), 0);
        for (String id : order) {
            int current = depth.getOrDefault(id, 0);
            for (TeamPlan.PlanEdge edge : outgoing.getOrDefault(id, List.of())) {
                depth.merge(edge.target(), current + 1, Math::max);
            }
        }
        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxDepth > TeamTemplate.MAX_DEPTH) {
            errors.add(
                    PlanError.of(
                            "DEPTH_EXCEEDED",
                            "计划深度超过 " + TeamTemplate.MAX_DEPTH + "：实际=" + maxDepth));
        }
        if (end != null && !depth.containsKey(end.id())) {
            errors.add(PlanError.of("UNREACHABLE_END", "START 无法到达 END"));
        }
        for (String id : nodeIds) {
            if (!depth.containsKey(id)) {
                errors.add(PlanError.at("UNREACHABLE_NODE", id, "节点从 START 不可达"));
            }
        }
    }

    private static boolean reachable(
            String from, String to, Map<String, List<TeamPlan.PlanEdge>> outgoing) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (TeamPlan.PlanEdge edge : outgoing.getOrDefault(current, List.of())) {
                if (edge.target().equals(to)) {
                    return true;
                }
                stack.push(edge.target());
            }
        }
        return false;
    }

    private static TeamPlan.PlanNode findAgent(
            List<TeamPlan.PlanNode> nodes, TeamRole role) {
        return nodes.stream()
                .filter(node -> role.name().equalsIgnoreCase(node.role()))
                .findFirst()
                .orElse(null);
    }

    private static TeamPlan.PlanNode singleNode(
            Map<String, TeamPlan.PlanNode> nodes,
            String type,
            String label,
            List<PlanError> errors) {
        List<TeamPlan.PlanNode> matched =
                nodes.values().stream().filter(node -> type.equals(node.type())).toList();
        if (matched.size() != 1) {
            errors.add(
                    PlanError.of(
                            "START_END",
                            "流程必须且只能有一个 " + label + "，实际=" + matched.size()));
            return null;
        }
        return matched.get(0);
    }
}
