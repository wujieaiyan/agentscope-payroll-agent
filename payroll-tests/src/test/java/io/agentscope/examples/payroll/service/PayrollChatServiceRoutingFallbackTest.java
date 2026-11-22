package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.router.RouteDecision;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollChatServiceRoutingFallbackTest {

    private static final AgentDefinition CHAT_AGENT =
            agent("chat-001", "情感专家", "闲聊、情感支持与通用对话", "情感", "闲聊", "通用");
    private static final AgentDefinition POETRY_AGENT =
            agent("poetry-001", "诗词专家", "古体诗与现代诗创作", "诗词", "文学", "创作");
    private static final AgentDefinition CALCULATOR_AGENT =
            agent("calc-001", "薪酬计算专家", "工资核算", RoleSkills.CALCULATOR);

    private static final List<AgentDefinition> CANDIDATES =
            List.of(CHAT_AGENT, POETRY_AGENT, CALCULATOR_AGENT);

    @Test
    @DisplayName("问候语即使被模型判给流水线，也兜底到闲聊类智能体")
    void greetingOverridesExplicitPipelineDecision() {
        RouteDecision pipeline = routed(RouteDecision.TARGET_PIPELINE, null, 0.82);

        RouteDecision decision =
                PayrollChatService.nonPayrollFallback("你好", false, CANDIDATES, null, pipeline);

        assertEquals(RouteDecision.TARGET_AGENT, decision.target());
        assertEquals(CHAT_AGENT.id(), decision.agent().id());
        assertTrue(decision.routed());
    }

    @Test
    @DisplayName("创作类问题被误判流水线时兜底到诗词专家")
    void creativeRequestOverridesExplicitPipelineDecision() {
        RouteDecision pipeline = routed(RouteDecision.TARGET_PIPELINE, null, 0.9);

        RouteDecision decision =
                PayrollChatService.nonPayrollFallback(
                        "写一首关于加班的诗词", false, CANDIDATES, null, pipeline);

        assertEquals(POETRY_AGENT.id(), decision.agent().id());
    }

    @Test
    @DisplayName("薪酬问题即使被模型判给流水线也保持不变")
    void payrollQuestionKeepsPipelineDecision() {
        RouteDecision pipeline = routed(RouteDecision.TARGET_PIPELINE, null, 0.8);

        RouteDecision decision =
                PayrollChatService.nonPayrollFallback("核算 EMP001 的工资", true, CANDIDATES, null, pipeline);

        assertSame(pipeline, decision);
    }

    @Test
    @DisplayName("模型已选中智能体或团队时不做兜底改写")
    void routedAgentDecisionIsPreserved() {
        RouteDecision routedAgent = routed(RouteDecision.TARGET_AGENT, CHAT_AGENT, 0.9);
        RouteDecision routedTeam = routed(RouteDecision.TARGET_TEAM, null, 0.9);

        assertSame(
                routedAgent,
                PayrollChatService.nonPayrollFallback("你好", false, CANDIDATES, null, routedAgent));
        assertSame(
                routedTeam,
                PayrollChatService.nonPayrollFallback("你好", false, CANDIDATES, null, routedTeam));
    }

    @Test
    @DisplayName("路由未生效时按语义兜底，偏好执行者优先")
    void unroutedDecisionFallsBackToPreferredAgent() {
        RouteDecision failed = RouteDecision.fallback("模型超时");

        RouteDecision decision =
                PayrollChatService.nonPayrollFallback("帮我写一段散文", false, CANDIDATES, POETRY_AGENT, failed);

        assertEquals(POETRY_AGENT.id(), decision.agent().id());
        assertEquals(RouteDecision.KB_SOURCE_FALLBACK, decision.kbSource());
    }

    @Test
    @DisplayName("没有匹配领域时保留原决策，不臆造目标")
    void unmatchedQuestionKeepsOriginalDecision() {
        RouteDecision failed = RouteDecision.fallback("模型超时");

        RouteDecision decision =
                PayrollChatService.nonPayrollFallback("今天天气怎么样", false, CANDIDATES, null, failed);

        assertSame(failed, decision);
        assertNull(decision.agent());
    }

    private static RouteDecision routed(String target, AgentDefinition agent, double confidence) {
        return new RouteDecision(
                target, agent, "测试决策", confidence, null, false, RouteDecision.KB_NONE, "");
    }

    private static AgentDefinition agent(String id, String name, String description, String... tags) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentDefinition(
                id,
                name,
                "🤖",
                description,
                List.of(tags),
                List.of("test"),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                now,
                now);
    }
}
