package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流程 DAG 执行器：按入边是否全部确定推进，同一层就绪节点并行执行。
 *
 * <p>条件节点只评估预设判断，未选中的出边标记为阻断；汇合节点等待全部入边确定，
 * 只要有任一入边被选中就执行，否则跳过并继续阻断下游。结束节点不调用模型，
 * 最终答复取选中路径上层级最深的智能体输出。
 */
public final class AgentFlowExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentFlowExecutor.class);
    private static final int MAX_PARALLEL = 8;

    /** 单节点执行结果；条件节点文本为空。 */
    public record NodeResult(String text, List<CalcTarget> targets, boolean toolError) {
        public NodeResult {
            text = text == null ? "" : text;
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * 流程执行结果。
     *
     * @param streamedText 已经实时推送到回答流的文本（未推流时为空），调用方据此只补发尾部差量
     */
    public record Outcome(
            String reply,
            List<CalcTarget> targets,
            Map<String, NodeResult> nodeResults,
            String streamedText) {
        public Outcome {
            nodeResults = nodeResults == null ? Map.of() : Map.copyOf(nodeResults);
            streamedText = streamedText == null ? "" : streamedText;
        }

        public Outcome(
                String reply, List<CalcTarget> targets, Map<String, NodeResult> nodeResults) {
            this(reply, targets, nodeResults, "");
        }
    }

    @FunctionalInterface
    public interface NodeRunner {
        /**
         * 执行单个节点。
         *
         * @param answerStream true 表示该节点输出应实时推送到回答流（text 事件），
         *     而不是只进入节点记录；由执行器挑选的末端答复节点获得该标记
         */
        NodeResult run(
                FlowNode node, String input, ChatStreamListener listener, boolean answerStream);
    }

    private enum Status {
        PENDING,
        DONE,
        SKIPPED
    }

    private record NodeRun(FlowNode node, NodeResult result, Boolean conditionValue) {
    }

    private AgentFlowExecutor() {
    }

    public static Outcome execute(
            FlowDefinition flow,
            String baseInput,
            ChatStreamListener listener,
            NodeRunner runner) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(runner, "runner");
        ChatStreamListener events = listener == null ? ChatStreamListener.NOOP : listener;

        Map<String, FlowNode> nodes = indexNodes(flow);
        Map<String, List<FlowEdge>> incoming = new HashMap<>();
        Map<String, List<FlowEdge>> outgoing = new HashMap<>();
        nodes.keySet().forEach(id -> {
            incoming.put(id, new ArrayList<>());
            outgoing.put(id, new ArrayList<>());
        });
        for (FlowEdge edge : flow.edges()) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                throw new PayrollException(
                        "流程连线引用了不存在的节点：" + edge.source() + " → " + edge.target());
            }
            outgoing.get(edge.source()).add(edge);
            incoming.get(edge.target()).add(edge);
        }

        FlowNode start = findType(nodes, FlowNode.TYPE_START);
        FlowNode end = findType(nodes, FlowNode.TYPE_END);
        if (start == null || end == null) {
            throw new PayrollException("流程缺少开始或结束节点");
        }

        Map<String, Status> status = new HashMap<>();
        nodes.keySet().forEach(id -> status.put(id, Status.PENDING));
        Map<String, NodeResult> results = new ConcurrentHashMap<>();
        Map<String, Boolean> edgeTaken = new HashMap<>();
        Map<String, Integer> ranks = ranks(nodes, outgoing, start);
        Map<String, Set<String>> ancestors = ancestors(nodes.keySet(), incoming);
        // 只有唯一的末端智能体才实时推流，避免并行分支同时写入同一条回答流。
        String answerNodeId = electAnswerNode(nodes, outgoing);
        StringBuilder streamedAnswer = new StringBuilder();
        if (answerNodeId != null) {
            log.info("[流程编排] 实时推流节点：{}", answerNodeId);
        }

        status.put(start.id(), Status.DONE);
        for (FlowEdge edge : outgoing.get(start.id())) {
            edgeTaken.put(edge.id(), Boolean.TRUE);
        }

        ExecutorService pool = newPool(nodes.size());
        long startedAt = System.currentTimeMillis();
        try {
            while (true) {
                List<FlowNode> ready = new ArrayList<>();
                boolean changed = false;
                for (FlowNode node : nodes.values()) {
                    if (status.get(node.id()) != Status.PENDING) {
                        continue;
                    }
                    List<FlowEdge> ins = incoming.get(node.id());
                    if (!ins.stream().allMatch(edge -> edgeTaken.containsKey(edge.id()))) {
                        continue;
                    }
                    boolean anyTaken = ins.stream()
                            .anyMatch(edge -> Boolean.TRUE.equals(edgeTaken.get(edge.id())));
                    if (!anyTaken) {
                        status.put(node.id(), Status.SKIPPED);
                        blockOutgoing(node, outgoing, edgeTaken);
                        log.info("[流程编排] 节点跳过：{}（没有选中的入边）", node.id());
                        changed = true;
                        continue;
                    }
                    if (FlowNode.TYPE_END.equals(FlowValidator.upper(node.type()))) {
                        status.put(node.id(), Status.DONE);
                        results.put(node.id(), new NodeResult("", List.of(), false));
                        log.info("[流程编排] 到达结束节点：{}", node.id());
                        changed = true;
                        continue;
                    }
                    ready.add(node);
                }
                if (ready.isEmpty()) {
                    if (changed) {
                        continue;
                    }
                    break;
                }

                List<Future<NodeRun>> futures = new ArrayList<>();
                for (FlowNode node : ready) {
                    boolean answerStream = node.id().equals(answerNodeId);
                    ChatStreamListener nodeEvents =
                            answerStream ? new AnswerStreamEvents(events, streamedAnswer) : events;
                    futures.add(pool.submit(() -> runNode(
                            node, baseInput, nodeEvents, runner, incoming, edgeTaken, results,
                            ancestors, nodes, ranks, answerStream)));
                }
                for (int i = 0; i < ready.size(); i++) {
                    FlowNode node = ready.get(i);
                    NodeRun run = await(futures.get(i), node);
                    status.put(node.id(), Status.DONE);
                    results.put(node.id(), run.result());
                    boolean condition = FlowNode.TYPE_CONDITION.equals(
                            FlowValidator.upper(node.type()));
                    for (FlowEdge edge : outgoing.get(node.id())) {
                        boolean taken = !condition
                                || edge.trueBranch() == Boolean.TRUE.equals(run.conditionValue());
                        edgeTaken.put(edge.id(), taken);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (status.get(end.id()) != Status.DONE) {
            throw new PayrollException("流程未能到达结束节点，请检查分支连线是否连通");
        }
        Set<String> contributing = contributing(end.id(), incoming, edgeTaken, status, results);
        List<String> answered = contributing.stream()
                .filter(id -> {
                    NodeResult result = results.get(id);
                    return result != null && !result.text().isBlank();
                })
                .toList();
        if (answered.isEmpty()) {
            throw new PayrollException("流程结束但没有产生答复内容，请检查智能体节点输出");
        }
        int maxRank = answered.stream()
                .mapToInt(id -> ranks.getOrDefault(id, 0))
                .max()
                .orElse(0);
        List<String> finalists = answered.stream()
                .filter(id -> ranks.getOrDefault(id, 0) == maxRank)
                .sorted()
                .toList();
        String reply = finalists.stream()
                .map(id -> results.get(id).text().trim())
                .distinct()
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow(() -> new PayrollException("流程没有产生最终答复"));

        LinkedHashSet<CalcTarget> targets = new LinkedHashSet<>();
        for (String id : contributing) {
            NodeResult result = results.get(id);
            if (result != null) {
                targets.addAll(result.targets());
            }
        }
        log.info(
                "[流程编排] 流程 {} 执行完成：节点={}，跳过={}，答复长度={}，耗时={}ms",
                flow.id(),
                results.size(),
                status.values().stream().filter(value -> value == Status.SKIPPED).count(),
                reply.length(),
                System.currentTimeMillis() - startedAt);
        return new Outcome(reply, List.copyOf(targets), results, streamedAnswer.toString());
    }

    private static Map<String, FlowNode> indexNodes(FlowDefinition flow) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        for (FlowNode node : flow.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank()) {
                throw new PayrollException("流程存在空节点或空节点 id");
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw new PayrollException("流程节点 id 重复：" + node.id());
            }
        }
        if (nodes.isEmpty()) {
            throw new PayrollException("流程没有节点");
        }
        return nodes;
    }

    private static FlowNode findType(Map<String, FlowNode> nodes, String type) {
        return nodes.values().stream()
                .filter(node -> type.equals(FlowValidator.upper(node.type())))
                .findFirst()
                .orElse(null);
    }

    private static NodeRun runNode(
            FlowNode node,
            String baseInput,
            ChatStreamListener events,
            NodeRunner runner,
            Map<String, List<FlowEdge>> incoming,
            Map<String, Boolean> edgeTaken,
            Map<String, NodeResult> results,
            Map<String, Set<String>> ancestors,
            Map<String, FlowNode> nodes,
            Map<String, Integer> ranks,
            boolean answerStream) {
        String type = FlowValidator.upper(node.type());
        if (FlowNode.TYPE_CONDITION.equals(type)) {
            boolean value = evaluate(
                    node, ancestors.getOrDefault(node.id(), Set.of()), results);
            events.onPhase("条件判断：" + node.label() + " → " + (value ? "是" : "否"));
            log.info("[流程编排] 条件判断：{}（{}）= {}", node.id(), node.condition(), value);
            return new NodeRun(node, new NodeResult("", List.of(), false), value);
        }
        String input = buildInput(
                node, baseInput, incoming, edgeTaken, results, nodes, ranks);
        long startedAt = System.currentTimeMillis();
        NodeResult result = runner.run(node, input, events, answerStream);
        log.info(
                "[流程编排] 节点完成：{}（{}），耗时={}ms，输出={}字，核算对象={}",
                node.id(),
                node.label(),
                System.currentTimeMillis() - startedAt,
                result.text().length(),
                result.targets().size());
        return new NodeRun(node, result, null);
    }

    /**
     * 选出唯一的“末端智能体”作为实时推流节点：它的出边经过条件/结束节点后
     * 不再到达任何智能体节点，因此其输出就是本次流程的最终答复。
     *
     * <p>存在多个末端智能体（并行分支或条件两条腿各自收尾）时返回 null，
     * 保持“流程结束后一次性给出答复”，避免多条分支交织进同一个回答流。
     */
    private static String electAnswerNode(
            Map<String, FlowNode> nodes, Map<String, List<FlowEdge>> outgoing) {
        List<String> terminals = new ArrayList<>();
        for (FlowNode node : nodes.values()) {
            if (!FlowNode.TYPE_AGENT.equals(FlowValidator.upper(node.type()))) {
                continue;
            }
            if (!reachesAgent(node.id(), outgoing, nodes)) {
                terminals.add(node.id());
            }
        }
        return terminals.size() == 1 ? terminals.get(0) : null;
    }

    private static boolean reachesAgent(
            String startId, Map<String, List<FlowEdge>> outgoing, Map<String, FlowNode> nodes) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(startId);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (FlowEdge edge : outgoing.getOrDefault(current, List.of())) {
                String target = edge.target();
                if (!visited.add(target)) {
                    continue;
                }
                FlowNode next = nodes.get(target);
                if (next != null && FlowNode.TYPE_AGENT.equals(FlowValidator.upper(next.type()))) {
                    return true;
                }
                stack.push(target);
            }
        }
        return false;
    }

    /** 转发全部事件，并记录真正推送到回答流（text 事件）的文本，供收尾时补齐差量。 */
    private static final class AnswerStreamEvents implements ChatStreamListener {

        private final ChatStreamListener delegate;
        private final StringBuilder recorded;

        AnswerStreamEvents(ChatStreamListener delegate, StringBuilder recorded) {
            this.delegate = delegate == null ? ChatStreamListener.NOOP : delegate;
            this.recorded = recorded;
        }

        @Override
        public void onStart(String model) {
            delegate.onStart(model);
        }

        @Override
        public void onAgentStart(String agent, String label) {
            delegate.onAgentStart(agent, label);
        }

        @Override
        public void onAgentOutput(String agent, String delta) {
            delegate.onAgentOutput(agent, delta);
        }

        @Override
        public void onAgentEnd(String agent) {
            delegate.onAgentEnd(agent);
        }

        @Override
        public void onThinking(String delta) {
            delegate.onThinking(delta);
        }

        @Override
        public void onPhase(String label) {
            delegate.onPhase(label);
        }

        @Override
        public void onContextCompressed(int compressed, int kept, int summarizedTotal) {
            delegate.onContextCompressed(compressed, kept, summarizedTotal);
        }

        @Override
        public void onToolStart(String toolCallId, String toolName) {
            delegate.onToolStart(toolCallId, toolName);
        }

        @Override
        public void onToolArgs(String toolCallId, String delta) {
            delegate.onToolArgs(toolCallId, delta);
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, String state) {
            delegate.onToolResult(toolCallId, toolName, state);
        }

        @Override
        public void onToolResult(
                String toolCallId, String toolName, String state, ChatToolTrace trace) {
            delegate.onToolResult(toolCallId, toolName, state, trace);
        }

        @Override
        public void onText(String delta) {
            if (delta != null && !delta.isEmpty()) {
                synchronized (recorded) {
                    recorded.append(delta);
                }
            }
            delegate.onText(delta);
        }

        @Override
        public void onActionProposal(ActionProposal proposal) {
            delegate.onActionProposal(proposal);
        }

        @Override
        public void onDone(PayrollChat.Reply reply) {
            delegate.onDone(reply);
        }
    }

    private static NodeRun await(Future<NodeRun> future, FlowNode node) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PayrollException("流程执行被中断：" + node.id());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PayrollException(
                    "流程节点「" + node.label() + "」执行失败：" + cause.getMessage());
        }
    }

    /**
     * 拼接节点输入：用户问题 + 所有经选中路径可达的上游记录。
     *
     * <p>不只取直接上游，是为了让“验证 → 核算”这类串联节点仍能看到计算记录；
     * 兄弟分支的输出不会被带进来，避免并行分支互相污染。段落标题去掉“①②”编号，
     * 与角色技能里“【计算智能体记录】/【验证智能体记录】”的引用保持一致；
     * 无输出的已执行节点保留占位，让下游知道该环节没有结论。
     */
    private static String buildInput(
            FlowNode node,
            String baseInput,
            Map<String, List<FlowEdge>> incoming,
            Map<String, Boolean> edgeTaken,
            Map<String, NodeResult> results,
            Map<String, FlowNode> nodes,
            Map<String, Integer> ranks) {
        String contextMode = FlowValidator.upper(node.contextMode());
        if (FlowNode.CONTEXT_NONE.equals(contextMode)) {
            return baseInput == null ? "" : baseInput;
        }
        Set<String> upstream = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(node.id());
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (FlowEdge edge : incoming.getOrDefault(current, List.of())) {
                if (!Boolean.TRUE.equals(edgeTaken.get(edge.id()))) {
                    continue;
                }
                String source = edge.source();
                if (results.containsKey(source)) {
                    upstream.add(source);
                }
                stack.push(source);
            }
        }
        if (upstream.isEmpty()) {
            return baseInput == null ? "" : baseInput;
        }
        // 自动策略只传递带核算对象的数据流水线；纯文本角色串联默认隔离，
        // 需要串联时由管理员显式选择 UPSTREAM。
        if (!FlowNode.CONTEXT_UPSTREAM.equals(contextMode)
                && upstream.stream().noneMatch(id -> {
                    NodeResult result = results.get(id);
                    return result != null && !result.targets().isEmpty();
                })) {
            return baseInput == null ? "" : baseInput;
        }
        StringBuilder input = new StringBuilder("【用户问题】\n");
        input.append(baseInput == null ? "" : baseInput);
        List<String> ordered = upstream.stream()
                .sorted(Comparator.comparingInt((String id) -> ranks.getOrDefault(id, 0))
                        .thenComparing(id -> id))
                .toList();
        LinkedHashSet<String> targetLines = new LinkedHashSet<>();
        for (String id : ordered) {
            NodeResult result = results.get(id);
            if (result == null) {
                continue;
            }
            FlowNode source = nodes.get(id);
            input.append("\n\n【")
                    .append(recordTitle(source, id))
                    .append("记录】\n")
                    .append(result.text().isBlank() ? "（本节点未返回内容）" : result.text().trim());
            for (CalcTarget target : result.targets()) {
                targetLines.add("- " + target.employeeId() + " | " + target.period());
            }
        }
        if (!targetLines.isEmpty()) {
            input.append("\n\n【上游核算对象（最多处理前 3 个）】\n");
            int limit = 0;
            for (String line : targetLines) {
                if (limit++ == 3) {
                    break;
                }
                input.append(line).append("\n");
            }
        }
        input.append("\n\n请按你的角色职责处理以上记录。");
        return input.toString();
    }

    /** 去掉节点名称里的“① ②”等装饰前缀，便于角色技能按固定标题引用。 */
    private static String recordTitle(FlowNode node, String fallbackId) {
        String label = node == null ? "" : node.label();
        String title = label.replaceFirst("^[\\s①-⑳]+", "").trim();
        return title.isEmpty() ? fallbackId : title;
    }

    private static boolean evaluate(
            FlowNode node, Set<String> ancestorIds, Map<String, NodeResult> results) {
        String condition = FlowValidator.upper(node.condition());
        if (FlowNode.CONDITION_HAS_TARGETS.equals(condition)) {
            return ancestorIds.stream()
                    .map(results::get)
                    .filter(Objects::nonNull)
                    .anyMatch(result -> !result.targets().isEmpty());
        }
        if (FlowNode.CONDITION_HAS_TOOL_ERROR.equals(condition)) {
            return ancestorIds.stream()
                    .map(results::get)
                    .filter(Objects::nonNull)
                    .anyMatch(NodeResult::toolError);
        }
        if (FlowNode.CONDITION_OUTPUT_CONTAINS.equals(condition)) {
            String keyword = FlowValidator.trim(node.conditionValue()).toLowerCase(Locale.ROOT);
            if (keyword.isEmpty()) {
                return false;
            }
            return ancestorIds.stream()
                    .map(results::get)
                    .filter(Objects::nonNull)
                    .anyMatch(result -> result.text().toLowerCase(Locale.ROOT).contains(keyword));
        }
        return false;
    }

    private static Map<String, Set<String>> ancestors(
            Set<String> nodeIds, Map<String, List<FlowEdge>> incoming) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String id : nodeIds) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(id);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                for (FlowEdge edge : incoming.getOrDefault(current, List.of())) {
                    String source = edge.source();
                    if (visited.add(source)) {
                        stack.push(source);
                    }
                }
            }
            result.put(id, visited);
        }
        return result;
    }

    private static Map<String, Integer> ranks(
            Map<String, FlowNode> nodes,
            Map<String, List<FlowEdge>> outgoing,
            FlowNode start) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> indegree.put(id, 0));
        for (List<FlowEdge> edges : outgoing.values()) {
            for (FlowEdge edge : edges) {
                indegree.computeIfPresent(edge.target(), (key, value) -> value + 1);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put(start.id(), 0);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentRank = ranks.getOrDefault(current, 0);
            for (FlowEdge edge : outgoing.getOrDefault(current, List.of())) {
                ranks.merge(edge.target(), currentRank + 1, Math::max);
                int degree = indegree.getOrDefault(edge.target(), 0) - 1;
                indegree.put(edge.target(), degree);
                if (degree == 0) {
                    queue.add(edge.target());
                }
            }
        }
        return ranks;
    }

    private static Set<String> contributing(
            String endId,
            Map<String, List<FlowEdge>> incoming,
            Map<String, Boolean> edgeTaken,
            Map<String, Status> status,
            Map<String, NodeResult> results) {
        Set<String> contributing = new LinkedHashSet<>();
        Set<String> expanded = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(endId);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!expanded.add(current)) {
                continue;
            }
            for (FlowEdge edge : incoming.getOrDefault(current, List.of())) {
                if (!Boolean.TRUE.equals(edgeTaken.get(edge.id()))) {
                    continue;
                }
                String source = edge.source();
                if (status.get(source) == Status.DONE && results.containsKey(source)) {
                    contributing.add(source);
                    stack.push(source);
                }
            }
        }
        return contributing;
    }

    private static void blockOutgoing(
            FlowNode node,
            Map<String, List<FlowEdge>> outgoing,
            Map<String, Boolean> edgeTaken) {
        for (FlowEdge edge : outgoing.getOrDefault(node.id(), List.of())) {
            edgeTaken.put(edge.id(), Boolean.FALSE);
        }
    }

    private static ExecutorService newPool(int nodeCount) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(
                Math.min(MAX_PARALLEL, Math.max(1, nodeCount)),
                runnable -> {
                    Thread thread = new Thread(runnable, "flow-node-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
