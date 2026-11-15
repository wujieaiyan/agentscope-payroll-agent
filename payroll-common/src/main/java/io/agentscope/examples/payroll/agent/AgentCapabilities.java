package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowNode;
import java.util.Locale;
import java.util.Map;

/**
 * 能力声明与推断（规格 6.2）：显式 {@code capabilities} 优先，未声明时按角色与标签回退到旧规则，
 * 保证存量 Nacos 配置无需改动即具备与今天一致的行为。
 */
public final class AgentCapabilities {

    public static final String KB_READ = AgentDefinition.CAPABILITY_KB_READ;
    public static final String KB_ADMIN_ROLE = "KB_CONFIG";

    private AgentCapabilities() {
    }

    /** 智能体是否具备只读检索能力；显式声明非空时不看标签。 */
    public static boolean supportsKbRead(AgentDefinition agent) {
        if (agent == null) {
            return false;
        }
        if (!agent.capabilities().isEmpty()) {
            return agent.declares(KB_READ);
        }
        return inferredKbRead(agent);
    }

    /** 流程节点可额外带角色：KB_CONFIG 角色天然具备检索能力。 */
    public static boolean supportsKbRead(String role, AgentDefinition agent) {
        if (role != null && KB_ADMIN_ROLE.equalsIgnoreCase(role.trim())) {
            return true;
        }
        return supportsKbRead(agent);
    }

    /**
     * 流程能力：显式声明优先（可为空表示明确不具备）；未声明时只要任一智能体节点具备 kb:read
     * 即视作具备，避免存量流程因缺字段而失效。
     */
    public static boolean flowSupportsKbRead(
            FlowDefinition flow, Map<String, AgentDefinition> agents) {
        if (flow == null) {
            return false;
        }
        if (!flow.capabilities().isEmpty()) {
            return flow.declares(KB_READ);
        }
        if (agents == null || agents.isEmpty()) {
            return false;
        }
        for (FlowNode node : flow.nodes()) {
            if (!FlowNode.TYPE_AGENT.equalsIgnoreCase(node.type())) {
                continue;
            }
            AgentDefinition agent = agents.get(node.agentId());
            if (supportsKbRead(node.id(), agent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inferredKbRead(AgentDefinition agent) {
        for (String tag : agent.tags()) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains("kb")) {
                return true;
            }
        }
        return false;
    }
}
