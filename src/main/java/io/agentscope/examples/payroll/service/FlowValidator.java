package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 流程定义校验：结构、引用、分支锚点、无环与可达性。 */
public final class FlowValidator {

    public static final int MAX_NODES = 30;
    public static final int MAX_EDGES = 60;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    private static final Set<String> TYPES = Set.of(
            FlowNode.TYPE_START,
            FlowNode.TYPE_AGENT,
            FlowNode.TYPE_CONDITION,
            FlowNode.TYPE_END);

    private static final Set<String> CONDITIONS = Set.of(
            FlowNode.CONDITION_HAS_TARGETS,
            FlowNode.CONDITION_HAS_TOOL_ERROR,
            FlowNode.CONDITION_OUTPUT_CONTAINS);

    private FlowValidator() {
    }

    public static boolean isCondition(String condition) {
        return CONDITIONS.contains(upper(condition));
    }

    public static List<String> validate(FlowDefinition flow, Set<String> knownAgentIds) {
        List<String> errors = new ArrayList<>();
        if (flow == null) {
            return List.of("流程不能为空");
        }
        String name = trim(flow.name());
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            errors.add("流程名称必填且不超过 " + MAX_NAME_LENGTH + " 字");
        }
        if (trim(flow.description()).length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("流程描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        List<FlowNode> nodes = flow.nodes();
        List<FlowEdge> edges = flow.edges();
        if (nodes.isEmpty()) {
            errors.add("流程至少包含一个节点");
            return List.copyOf(errors);
        }
        if (nodes.size() > MAX_NODES) {
            errors.add("流程节点不能超过 " + MAX_NODES + " 个");
        }
        if (edges.size() > MAX_EDGES) {
            errors.add("流程连线不能超过 " + MAX_EDGES + " 条");
        }

        Map<String, FlowNode> byId = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            String id = trim(node.id());
            if (id.isEmpty()) {
                errors.add("存在空节点 id");
                continue;
            }
            if (byId.putIfAbsent(id, node) != null) {
                errors.add("节点 id 重复：" + id);
                continue;
            }
            String type = upper(node.type());
            if (!TYPES.contains(type)) {
                errors.add("节点 " + id + " 类型非法：" + node.type());
                continue;
            }
            if (FlowNode.TYPE_AGENT.equals(type)) {
                String agentId = trim(node.agentId());
                if (agentId.isEmpty()) {
                    errors.add("智能体节点必须选择智能体：" + id);
                } else if (knownAgentIds != null && !knownAgentIds.contains(agentId)) {
                    errors.add("节点 " + id + " 引用的智能体不存在：" + agentId);
                }
            } else if (FlowNode.TYPE_CONDITION.equals(type)) {
                String condition = upper(node.condition());
                if (!CONDITIONS.contains(condition)) {
                    errors.add("条件节点 " + id + " 的条件非法：" + node.condition());
                } else if (FlowNode.CONDITION_OUTPUT_CONTAINS.equals(condition)
                        && trim(node.conditionValue()).isEmpty()) {
                    errors.add("条件节点 " + id + " 需要填写关键字");
                }
            }
        }

        Map<String, List<FlowEdge>> incoming = new HashMap<>();
        Map<String, List<FlowEdge>> outgoing = new HashMap<>();
        for (String id : byId.keySet()) {
            incoming.put(id, new ArrayList<>());
            outgoing.put(id, new ArrayList<>());
        }
        Set<String> edgeIds = new HashSet<>();
        Set<String> edgeKeys = new HashSet<>();
        for (FlowEdge edge : edges) {
            if (edge == null) {
                errors.add("存在空连线");
                continue;
            }
            String edgeId = trim(edge.id());
            if (edgeId.isEmpty()) {
                errors.add("存在空连线 id");
            } else if (!edgeIds.add(edgeId)) {
                errors.add("连线 id 重复：" + edgeId);
            }
            String source = trim(edge.source());
            String target = trim(edge.target());
            if (!byId.containsKey(source) || !byId.containsKey(target)) {
                errors.add("连线 " + edgeId + " 引用了不存在的节点");
                continue;
            }
            if (source.equals(target)) {
                errors.add("连线 " + edgeId + " 不能连接节点自身");
                continue;
            }
            String handle = upper(edge.sourceHandle());
            if (!handle.isEmpty()
                    && !FlowEdge.HANDLE_TRUE.equals(handle)
                    && !FlowEdge.HANDLE_FALSE.equals(handle)) {
                errors.add("连线 " + edgeId + " 的分支标记非法：" + edge.sourceHandle());
            }
            if (!edgeKeys.add(source + ">" + target + "#" + handle)) {
                errors.add("存在重复连线：" + source + " → " + target);
            }
            outgoing.get(source).add(edge);
            incoming.get(target).add(edge);
        }

        long starts = nodes.stream().filter(n -> FlowNode.TYPE_START.equals(upper(n.type()))).count();
        long ends = nodes.stream().filter(n -> FlowNode.TYPE_END.equals(upper(n.type()))).count();
        if (starts != 1) {
            errors.add("流程必须恰好包含一个开始节点");
        }
        if (ends != 1) {
            errors.add("流程必须恰好包含一个结束节点");
        }
        if (nodes.stream().noneMatch(n -> FlowNode.TYPE_AGENT.equals(upper(n.type())))) {
            errors.add("流程至少包含一个智能体节点");
        }

        for (FlowNode node : byId.values()) {
            String id = trim(node.id());
            String type = upper(node.type());
            List<FlowEdge> in = incoming.get(id);
            List<FlowEdge> out = outgoing.get(id);
            switch (type) {
                case FlowNode.TYPE_START -> {
                    if (!in.isEmpty()) {
                        errors.add("开始节点不能有入边：" + id);
                    }
                    if (out.size() != 1) {
                        errors.add("开始节点必须恰好有一条出边：" + id);
                    }
                }
                case FlowNode.TYPE_END -> {
                    if (in.isEmpty()) {
                        errors.add("结束节点至少需要一条入边：" + id);
                    }
                    if (!out.isEmpty()) {
                        errors.add("结束节点不能有出边：" + id);
                    }
                }
                case FlowNode.TYPE_AGENT -> {
                    if (in.isEmpty()) {
                        errors.add("智能体节点至少需要一条入边：" + id);
                    }
                    if (out.isEmpty()) {
                        errors.add("智能体节点至少需要一条出边：" + id);
                    }
                }
                case FlowNode.TYPE_CONDITION -> {
                    if (in.size() != 1) {
                        errors.add("条件节点必须恰好有一条入边：" + id);
                    }
                    if (out.size() != 2) {
                        errors.add("条件节点必须恰好有两条出边（是/否）：" + id);
                    } else {
                        long trueCount = out.stream()
                                .filter(e -> FlowEdge.HANDLE_TRUE.equals(upper(e.sourceHandle())))
                                .count();
                        long falseCount = out.stream()
                                .filter(e -> FlowEdge.HANDLE_FALSE.equals(upper(e.sourceHandle())))
                                .count();
                        if (trueCount != 1 || falseCount != 1) {
                            errors.add("条件节点 " + id + " 必须各有一条“是/否”出边");
                        }
                    }
                }
                default -> {
                }
            }
        }

        FlowNode start = byId.values().stream()
                .filter(n -> FlowNode.TYPE_START.equals(upper(n.type())))
                .findFirst()
                .orElse(null);
        FlowNode end = byId.values().stream()
                .filter(n -> FlowNode.TYPE_END.equals(upper(n.type())))
                .findFirst()
                .orElse(null);

        if (start != null && end != null && !byId.isEmpty()) {
            Set<String> reachable = traverse(start.id(), outgoing, FlowEdge::target);
            if (reachable.size() < byId.size()) {
                List<String> missing = byId.keySet().stream()
                        .filter(id -> !reachable.contains(id))
                        .toList();
                errors.add("以下节点无法从开始节点到达：" + missing);
            }
            Set<String> canReachEnd = traverse(end.id(), incoming, FlowEdge::source);
            if (canReachEnd.size() < byId.size()) {
                List<String> missing = byId.keySet().stream()
                        .filter(id -> !canReachEnd.contains(id))
                        .toList();
                errors.add("以下节点无法到达结束节点：" + missing);
            }
            if (hasCycle(byId.keySet(), outgoing)) {
                errors.add("流程中存在环，请删除回连的连线");
            }
        }
        return List.copyOf(errors);
    }

    private static Set<String> traverse(
            String start,
            Map<String, List<FlowEdge>> adjacency,
            java.util.function.Function<FlowEdge, String> next) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (FlowEdge edge : adjacency.getOrDefault(current, List.of())) {
                queue.add(next.apply(edge));
            }
        }
        return visited;
    }

    private static boolean hasCycle(Set<String> nodes, Map<String, List<FlowEdge>> outgoing) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.forEach(id -> indegree.put(id, 0));
        for (Map.Entry<String, List<FlowEdge>> entry : outgoing.entrySet()) {
            for (FlowEdge edge : entry.getValue()) {
                String target = trim(edge.target());
                indegree.computeIfPresent(target, (key, value) -> value + 1);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (FlowEdge edge : outgoing.getOrDefault(current, List.of())) {
                String target = trim(edge.target());
                Integer degree = indegree.get(target);
                if (degree != null && degree - 1 == 0) {
                    indegree.put(target, 0);
                    queue.add(target);
                } else if (degree != null) {
                    indegree.put(target, degree - 1);
                }
            }
        }
        return visited < nodes.size();
    }

    public static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static String upper(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }
}
