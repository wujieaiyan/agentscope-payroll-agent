package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentCapabilitiesTest {

    @Test
    @DisplayName("未声明时保持旧规则：KB 标签可检索，薪酬标签不可")
    void infersFromTagsWhenUndeclared() {
        assertTrue(AgentCapabilities.supportsKbRead(agent(List.of("KB"), List.of())));
        assertTrue(AgentCapabilities.supportsKbRead(agent(List.of("kb-config"), List.of())));
        assertFalse(AgentCapabilities.supportsKbRead(agent(List.of("薪酬"), List.of())));
        assertFalse(AgentCapabilities.supportsKbRead(agent(List.of(), List.of())));
    }

    @Test
    @DisplayName("显式声明 kb:read 在无 KB 标签时也可检索")
    void declaredKbReadEnablesSearch() {
        assertTrue(
                AgentCapabilities.supportsKbRead(
                        agent(List.of("薪酬"), List.of(AgentDefinition.CAPABILITY_KB_READ))));
        assertTrue(
                AgentCapabilities.supportsKbRead(
                        agent(List.of(), List.of("KB:READ"))));
    }

    @Test
    @DisplayName("显式声明非空但缺少 kb:read 时视为明确关闭，不再按标签推断")
    void declaredCapabilitiesOverrideTags() {
        assertFalse(
                AgentCapabilities.supportsKbRead(
                        agent(List.of("KB"), List.of(AgentDefinition.CAPABILITY_CALC))));
        assertFalse(
                AgentCapabilities.supportsKbRead(
                        agent(List.of("kb"), List.of(AgentDefinition.CAPABILITY_VIDEO))));
    }

    @Test
    @DisplayName("KB_CONFIG 角色天然具备检索，与智能体声明无关")
    void kbAdminRoleAlwaysReads() {
        assertTrue(
                AgentCapabilities.supportsKbRead(
                        "KB_CONFIG", agent(List.of("薪酬"), List.of(AgentDefinition.CAPABILITY_CALC))));
        assertTrue(PayrollAgentFactory.kbSearchNode("kb_config", null));
    }

    @Test
    @DisplayName("流程声明优先，未声明时按智能体节点推断")
    void flowCapabilityDeclaration() {
        AgentDefinition payroll = agent(List.of("薪酬"), List.of());
        AgentDefinition knowledge = agent(List.of("KB"), List.of());
        Map<String, AgentDefinition> agents =
                Map.of(payroll.id(), payroll, knowledge.id(), knowledge);

        FlowDefinition undeclared =
                flow(List.of(), node("n1", payroll.id()));
        assertFalse(AgentCapabilities.flowSupportsKbRead(undeclared, agents));

        FlowDefinition inferred =
                flow(List.of(), node("n1", knowledge.id()));
        assertTrue(AgentCapabilities.flowSupportsKbRead(inferred, agents));

        FlowDefinition declared =
                flow(List.of(AgentDefinition.CAPABILITY_KB_READ), node("n1", payroll.id()));
        assertTrue(AgentCapabilities.flowSupportsKbRead(declared, agents));

        FlowDefinition declaredOff =
                flow(List.of(AgentDefinition.CAPABILITY_CALC), node("n1", knowledge.id()));
        assertFalse(AgentCapabilities.flowSupportsKbRead(declaredOff, agents));
    }

    private static AgentDefinition agent(List<String> tags, List<String> capabilities) {
        return new AgentDefinition(
                "agent-" + tags.hashCode() + capabilities.hashCode(),
                "测试智能体",
                null,
                "测试",
                tags,
                List.of(),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null,
                capabilities);
    }

    private static FlowNode node(String id, String agentId) {
        return new FlowNode(id, FlowNode.TYPE_AGENT, id, agentId, null, null, 0.0, 0.0);
    }

    private static FlowDefinition flow(List<String> capabilities, FlowNode... nodes) {
        List<FlowNode> all = new java.util.ArrayList<>();
        all.add(new FlowNode("start", FlowNode.TYPE_START, "开始", null, null, null, 0.0, 0.0));
        all.addAll(List.of(nodes));
        all.add(new FlowNode("end", FlowNode.TYPE_END, "结束", null, null, null, 0.0, 0.0));
        return new FlowDefinition(
                "flow-test",
                "测试流程",
                "",
                all,
                List.<FlowEdge>of(),
                "tester",
                null,
                null,
                capabilities);
    }
}
