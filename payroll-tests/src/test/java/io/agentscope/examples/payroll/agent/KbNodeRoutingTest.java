package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbNodeRoutingTest {

    @Test
    @DisplayName("KB_CONFIG 角色节点挂载只读检索")
    void kbConfigRoleEnablesSearch() {
        assertTrue(PayrollAgentFactory.kbSearchNode("KB_CONFIG", agent(List.of())));
    }

    @Test
    @DisplayName("标签含 kb 的智能体在流程节点中也可检索")
    void kbTagEnablesSearch() {
        assertTrue(PayrollAgentFactory.kbSearchNode("QUERY", agent(List.of("KB"))));
        assertTrue(PayrollAgentFactory.kbSearchNode("", agent(List.of("kb-config"))));
    }

    @Test
    @DisplayName("薪酬角色默认不注册 kb_search")
    void payrollRoleStaysWithoutSearch() {
        assertFalse(PayrollAgentFactory.kbSearchNode("CALCULATOR", agent(List.of("薪酬"))));
        assertFalse(PayrollAgentFactory.kbSearchNode(null, agent(List.of())));
    }

    @Test
    @DisplayName("团队失败与兼容构造不带引用，成功结果可显式回传")
    void citationsAreCarriedExplicitly() {
        assertTrue(ActiveTeamService.Attempt.skipped().citations().isEmpty());
        PayrollChatService.DynamicFlowResult legacy =
                new PayrollChatService.DynamicFlowResult(
                        "ok", List.of(), List.of(), List.of(), List.of(), Map.of(), "");
        assertTrue(legacy.citations().isEmpty());
    }

    private static AgentDefinition agent(List<String> tags) {
        return new AgentDefinition(
                "kb-agent",
                "知识库智能体",
                null,
                "测试",
                tags,
                List.of("policy-knowledge"),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null);
    }
}
