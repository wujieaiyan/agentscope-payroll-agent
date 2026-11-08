package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentFlowExecutorTest {

    private static final String QUESTION = "算一下张伟 2026-10 的工资";

    @Test
    @DisplayName("线性流程把用户问题交给首个节点，并用最深节点文本作答")
    void runsLinearFlow() {
        List<String> inputs = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow =
                flow(List.of(start(), agent("a", "A"), agent("b", "B"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "b"),
                                edge("e3", "b", "end")));

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> {
                            inputs.add(node.id() + "|" + input);
                            return new AgentFlowExecutor.NodeResult(
                                    node.id().toUpperCase(), List.of(), false);
                        });

        assertEquals(List.of("a|" + QUESTION, "b|" + QUESTION + "\n\n## 上游节点：A\nA"), inputs);
        assertEquals("B", outcome.reply());
        assertTrue(outcome.nodeResults().containsKey("a"));
        assertTrue(outcome.nodeResults().containsKey("b"));
        assertTrue(outcome.nodeResults().containsKey("end"));
    }

    @Test
    @DisplayName("条件为真时只执行 TRUE 分支，FALSE 分支被跳过")
    void takesTrueBranch() {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow = branchFlow(FlowNode.CONDITION_HAS_TARGETS, null);

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> {
                            executed.add(node.id());
                            boolean calc = "calc".equals(node.id());
                            return new AgentFlowExecutor.NodeResult(
                                    node.id().toUpperCase(),
                                    calc
                                            ? List.of(new CalcTarget("EMP001", "2026-10"))
                                            : List.of(),
                                    false);
                        });

        assertEquals(List.of("calc", "yes"), executed);
        assertEquals("YES", outcome.reply());
        assertFalse(outcome.nodeResults().containsKey("no"));
    }

    @Test
    @DisplayName("没有核算对象时走 FALSE 分支，验证与核算节点不执行")
    void takesFalseBranch() {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow = branchFlow(FlowNode.CONDITION_HAS_TARGETS, null);

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> {
                            executed.add(node.id());
                            return new AgentFlowExecutor.NodeResult(
                                    node.id().toUpperCase(), List.of(), false);
                        });

        assertEquals(List.of("calc", "no"), executed);
        assertEquals("NO", outcome.reply());
        assertFalse(outcome.nodeResults().containsKey("yes"));
    }

    @Test
    @DisplayName("工具报错条件下选择 TRUE 分支")
    void evaluatesToolErrorCondition() {
        FlowDefinition flow = branchFlow(FlowNode.CONDITION_HAS_TOOL_ERROR, null);

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> new AgentFlowExecutor.NodeResult(
                                node.id().toUpperCase(),
                                List.of(),
                                "calc".equals(node.id())));

        assertEquals("YES", outcome.reply());
    }

    @Test
    @DisplayName("输出包含关键字条件下选择 TRUE 分支")
    void evaluatesOutputContainsCondition() {
        FlowDefinition flow = branchFlow(FlowNode.CONDITION_OUTPUT_CONTAINS, "工资");

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> new AgentFlowExecutor.NodeResult(
                                "calc".equals(node.id())
                                        ? "本月工资 10000 元"
                                        : node.id().toUpperCase(),
                                List.of(),
                                false));

        assertEquals("YES", outcome.reply());
    }

    @Test
    @DisplayName("同层节点真并行执行")
    void runsSameLayerInParallel() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow =
                flow(List.of(start(), agent("a", "A"), agent("b", "B"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "start", "b"),
                                edge("e3", "a", "end"), edge("e4", "b", "end")));

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> {
                            executed.add(node.id());
                            try {
                                barrier.await(3, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new PayrollException("并行执行未生效：" + e.getMessage());
                            }
                            return new AgentFlowExecutor.NodeResult(
                                    node.id().toUpperCase(), List.of(), false);
                        });

        assertEquals(2, executed.size(), executed.toString());
        assertEquals("A\n\nB", outcome.reply());
    }

    @Test
    @DisplayName("汇合节点接收所有选中上游的输出，且不包含兄弟分支")
    void mergesJoinInputs() {
        List<String> joinInputs = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow =
                flow(
                        List.of(start(), agent("calc", "CALC"), agent("a", "A"),
                                agent("b", "B"), agent("join", "JOIN"), end()),
                        List.of(
                                edge("e1", "start", "calc"),
                                edge("e2", "calc", "a"),
                                edge("e3", "calc", "b"),
                                edge("e4", "a", "join"),
                                edge("e5", "b", "join"),
                                edge("e6", "join", "end")));

        AgentFlowExecutor.execute(
                flow,
                QUESTION,
                ChatStreamListener.NOOP,
                (node, input, listener) -> {
                    if ("join".equals(node.id())) {
                        joinInputs.add(input);
                    }
                    return new AgentFlowExecutor.NodeResult(
                            node.id().toUpperCase(), List.of(), false);
                });

        String input = joinInputs.get(0);
        assertTrue(input.startsWith(QUESTION), input);
        assertTrue(input.contains("## 上游节点：CALC\nCALC"), input);
        assertTrue(input.contains("## 上游节点：A\nA"), input);
        assertTrue(input.contains("## 上游节点：B\nB"), input);
        assertTrue(input.indexOf("CALC") < input.indexOf("A"), input);
    }

    @Test
    @DisplayName("跳过节点会阻断下游，最终答复取有效分支")
    void propagatesSkips() {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        FlowDefinition flow =
                flow(
                        List.of(start(), agent("calc", "CALC"), condition("check"),
                                agent("deep", "DEEP"), agent("deeper", "DEEPER"),
                                agent("no", "NO"), end()),
                        List.of(
                                edge("e1", "start", "calc"),
                                edge("e2", "calc", "check"),
                                edge("e3", "check", "deep", FlowEdge.HANDLE_TRUE),
                                edge("e4", "deep", "deeper"),
                                edge("e5", "deeper", "end"),
                                edge("e6", "check", "no", FlowEdge.HANDLE_FALSE),
                                edge("e7", "no", "end")));

        AgentFlowExecutor.Outcome outcome =
                AgentFlowExecutor.execute(
                        flow,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) -> {
                            executed.add(node.id());
                            return new AgentFlowExecutor.NodeResult(
                                    node.id().toUpperCase(), List.of(), false);
                        });

        assertEquals(List.of("calc", "no"), executed);
        assertEquals("NO", outcome.reply());
        assertFalse(outcome.nodeResults().containsKey("deep"));
        assertFalse(outcome.nodeResults().containsKey("deeper"));
    }

    @Test
    @DisplayName("节点异常向上抛出并带上节点信息")
    void propagatesNodeFailure() {
        FlowDefinition flow =
                flow(List.of(start(), agent("a", "A"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> AgentFlowExecutor.execute(
                                flow,
                                QUESTION,
                                ChatStreamListener.NOOP,
                                (node, input, listener) -> {
                                    throw new PayrollException("模型不可用");
                                }));
        assertTrue(error.getMessage().contains("模型不可用"), error.getMessage());
    }

    @Test
    @DisplayName("缺少结束节点或没有可答复内容时给出明确错误")
    void rejectsIncompleteFlows() {
        FlowDefinition noEnd =
                flow(List.of(start(), agent("a", "A")),
                        List.of(edge("e1", "start", "a")));
        PayrollException missingEnd =
                assertThrows(
                        PayrollException.class,
                        () -> AgentFlowExecutor.execute(
                                noEnd,
                                QUESTION,
                                ChatStreamListener.NOOP,
                                (node, input, listener) ->
                                        new AgentFlowExecutor.NodeResult(
                                                "A", List.of(), false)));
        assertTrue(missingEnd.getMessage().contains("缺少开始或结束节点"), missingEnd.getMessage());

        FlowDefinition noAnswer =
                flow(List.of(start(), end()), List.of(edge("e1", "start", "end")));
        PayrollException empty =
                assertThrows(
                        PayrollException.class,
                        () -> AgentFlowExecutor.execute(
                                noAnswer,
                                QUESTION,
                                ChatStreamListener.NOOP,
                                (node, input, listener) ->
                                        new AgentFlowExecutor.NodeResult(
                                                "", List.of(), false)));
        assertTrue(empty.getMessage().contains("没有产生答复内容"), empty.getMessage());
    }

    @Test
    @DisplayName("拒绝引用不存在节点的连线与重复节点 id")
    void rejectsBrokenGraph() {
        FlowDefinition brokenEdge =
                flow(List.of(start(), agent("a", "A"), end()),
                        List.of(edge("e1", "start", "ghost"), edge("e2", "a", "end")));
        assertThrows(
                PayrollException.class,
                () -> AgentFlowExecutor.execute(
                        brokenEdge,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) ->
                                new AgentFlowExecutor.NodeResult("A", List.of(), false)));

        FlowDefinition duplicate =
                flow(List.of(start(), agent("a", "A"), agent("a", "B"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));
        assertThrows(
                PayrollException.class,
                () -> AgentFlowExecutor.execute(
                        duplicate,
                        QUESTION,
                        ChatStreamListener.NOOP,
                        (node, input, listener) ->
                                new AgentFlowExecutor.NodeResult("A", List.of(), false)));
    }

    /** start → calc → check；TRUE → yes → end，FALSE → no → end。 */
    private static FlowDefinition branchFlow(String condition, String conditionValue) {
        return flow(
                List.of(start(), agent("calc", "CALC"),
                        condition("check", condition, conditionValue),
                        agent("yes", "YES"), agent("no", "NO"), end()),
                List.of(
                        edge("e1", "start", "calc"),
                        edge("e2", "calc", "check"),
                        edge("e3", "check", "yes", FlowEdge.HANDLE_TRUE),
                        edge("e4", "yes", "end"),
                        edge("e5", "check", "no", FlowEdge.HANDLE_FALSE),
                        edge("e6", "no", "end")));
    }

    private static FlowDefinition flow(List<FlowNode> nodes, List<FlowEdge> edges) {
        LocalDateTime now = LocalDateTime.now();
        return new FlowDefinition(
                "flow-test", "测试流程", "", new ArrayList<>(nodes), new ArrayList<>(edges),
                "test", now, now);
    }

    private static FlowNode start() {
        return new FlowNode("start", FlowNode.TYPE_START, "开始", null, null, null, 0.0, 0.0);
    }

    private static FlowNode end() {
        return new FlowNode("end", FlowNode.TYPE_END, "结束", null, null, null, 0.0, 0.0);
    }

    private static FlowNode agent(String id, String name) {
        return new FlowNode(
                id, FlowNode.TYPE_AGENT, name, "agent-" + id, null, null, 0.0, 0.0);
    }

    private static FlowNode condition(String id) {
        return condition(id, FlowNode.CONDITION_HAS_TARGETS, null);
    }

    private static FlowNode condition(String id, String condition, String value) {
        return new FlowNode(id, FlowNode.TYPE_CONDITION, "条件", null, condition, value,
                0.0, 0.0);
    }

    private static FlowEdge edge(String id, String source, String target) {
        return new FlowEdge(id, source, target, null);
    }

    private static FlowEdge edge(String id, String source, String target, String handle) {
        return new FlowEdge(id, source, target, handle);
    }
}
