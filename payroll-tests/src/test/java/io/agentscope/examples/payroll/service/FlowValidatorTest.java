package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlowValidatorTest {

    private static final Set<String> AGENTS =
            Set.of(RoleSkills.CALCULATOR, RoleSkills.VALIDATOR, RoleSkills.ACCOUNTANT);

    @Test
    @DisplayName("内置标准流程通过校验")
    void builtinFlowIsValid() {
        assertEquals(
                List.of(),
                FlowValidator.validate(NacosFlowRegistry.builtinFlow(), AGENTS));
    }

    @Test
    @DisplayName("智能体节点必须选择已存在的智能体")
    void rejectsUnknownAgent() {
        FlowDefinition flow =
                flow(
                        List.of(start(), agent("a", ""), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));

        List<String> missing = FlowValidator.validate(flow, AGENTS);
        assertTrue(String.join("；", missing).contains("必须选择智能体"), missing.toString());

        FlowDefinition ghost =
                flow(
                        List.of(start(), agent("a", "ghost-agent"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));
        List<String> unknown = FlowValidator.validate(ghost, AGENTS);
        assertTrue(String.join("；", unknown).contains("引用的智能体不存在"), unknown.toString());
    }

    @Test
    @DisplayName("条件节点必须各有一条是/否出边，OUTPUT_CONTAINS 必须有关键字")
    void validatesConditionBranches() {
        FlowDefinition missingFalse =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR), condition("c"),
                                agent("b", RoleSkills.VALIDATOR), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "c"),
                                edge("e3", "c", "b", FlowEdge.HANDLE_TRUE),
                                edge("e4", "b", "end")));
        List<String> branchErrors = FlowValidator.validate(missingFalse, AGENTS);
        assertTrue(
                String.join("；", branchErrors).contains("必须恰好有两条出边"),
                branchErrors.toString());

        FlowDefinition noKeyword =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR),
                                condition("c", FlowNode.CONDITION_OUTPUT_CONTAINS, "  "),
                                agent("b", RoleSkills.VALIDATOR), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "c"),
                                edge("e3", "c", "b", FlowEdge.HANDLE_TRUE),
                                edge("e4", "c", "b", FlowEdge.HANDLE_FALSE),
                                edge("e5", "b", "end")));
        List<String> keywordErrors = FlowValidator.validate(noKeyword, AGENTS);
        assertTrue(
                String.join("；", keywordErrors).contains("需要填写关键字"),
                keywordErrors.toString());
    }

    @Test
    @DisplayName("拒绝环、不可达节点与无法到达结束节点的定义")
    void rejectsCyclesAndDeadEnds() {
        FlowDefinition cycle =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR),
                                agent("b", RoleSkills.VALIDATOR), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "b"),
                                edge("e3", "b", "a"),
                                edge("e4", "b", "end")));
        assertTrue(
                String.join("；", FlowValidator.validate(cycle, AGENTS)).contains("存在环"));

        FlowDefinition unreachable =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR),
                                agent("b", RoleSkills.VALIDATOR), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));
        List<String> unreachableErrors = FlowValidator.validate(unreachable, AGENTS);
        assertTrue(
                String.join("；", unreachableErrors).contains("无法从开始节点到达"),
                unreachableErrors.toString());

        FlowDefinition deadEnd =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR),
                                agent("b", RoleSkills.VALIDATOR),
                                agent("c", RoleSkills.ACCOUNTANT), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "end"),
                                edge("e3", "a", "b"),
                                edge("e4", "b", "c"),
                                edge("e5", "c", "b")));
        List<String> deadEndErrors = FlowValidator.validate(deadEnd, AGENTS);
        String deadEndText = String.join("；", deadEndErrors);
        assertTrue(deadEndText.contains("存在环"), deadEndText);
        assertTrue(deadEndText.contains("无法到达结束节点"), deadEndText);
    }

    @Test
    @DisplayName("允许智能体节点汇合多条入边")
    void allowsJoins() {
        FlowDefinition flow =
                flow(
                        List.of(start(), agent("a", RoleSkills.CALCULATOR),
                                agent("b", RoleSkills.VALIDATOR),
                                agent("c", RoleSkills.ACCOUNTANT), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "b"),
                                edge("e3", "a", "c"),
                                edge("e4", "b", "c"),
                                edge("e5", "c", "end")));

        assertEquals(List.of(), FlowValidator.validate(flow, AGENTS));
    }

    @Test
    @DisplayName("名称必填、长度受限，且必须恰好一个开始与结束节点")
    void validatesHeaderAndEndpoints() {
        FlowDefinition blank =
                new FlowDefinition(
                        null,
                        "  ",
                        "x".repeat(FlowValidator.MAX_DESCRIPTION_LENGTH + 1),
                        List.of(start(), agent("a", RoleSkills.CALCULATOR), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")),
                        null,
                        null,
                        null);
        List<String> errors = FlowValidator.validate(blank, AGENTS);
        assertTrue(String.join("；", errors).contains("流程名称必填"), errors.toString());
        assertTrue(String.join("；", errors).contains("流程描述不能超过"), errors.toString());

        FlowDefinition twoStarts =
                flow(
                        List.of(start(), start2(), agent("a", RoleSkills.CALCULATOR), end()),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "start-2", "a"),
                                edge("e3", "a", "end")));
        assertTrue(
                String.join("；", FlowValidator.validate(twoStarts, AGENTS))
                        .contains("恰好包含一个开始节点"));
    }

    @Test
    @DisplayName("未提供智能体目录时只校验非空引用")
    void knownAgentsOptional() {
        FlowDefinition flow =
                flow(
                        List.of(start(), agent("a", "custom-agent"), end()),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));

        assertFalse(FlowValidator.validate(flow, (java.util.Map<String, io.agentscope.examples.payroll.domain.AgentDefinition>) null).stream()
                .anyMatch(error -> error.contains("不存在")));
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

    private static FlowNode start2() {
        return new FlowNode("start-2", FlowNode.TYPE_START, "开始2", null, null, null, 0.0, 0.0);
    }

    private static FlowNode end() {
        return new FlowNode("end", FlowNode.TYPE_END, "结束", null, null, null, 0.0, 0.0);
    }

    private static FlowNode agent(String id, String agentId) {
        return new FlowNode(id, FlowNode.TYPE_AGENT, id, agentId, null, null, 0.0, 0.0);
    }

    private static FlowNode condition(String id) {
        return condition(id, FlowNode.CONDITION_HAS_TARGETS, null);
    }

    private static FlowNode condition(String id, String condition, String value) {
        return new FlowNode(id, FlowNode.TYPE_CONDITION, id, null, condition, value, 0.0, 0.0);
    }

    private static FlowEdge edge(String id, String source, String target) {
        return new FlowEdge(id, source, target, null);
    }

    private static FlowEdge edge(String id, String source, String target, String handle) {
        return new FlowEdge(id, source, target, handle);
    }
}
