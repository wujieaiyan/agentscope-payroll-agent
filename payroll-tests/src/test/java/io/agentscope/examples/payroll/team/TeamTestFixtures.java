package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P1 测试共用的智能体目录、意图与追溯构造。 */
final class TeamTestFixtures {

    private TeamTestFixtures() {
    }

    static AgentDefinition agent(String id, String skillId) {
        return agent(
                id,
                id,
                skillId,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "");
    }

    static AgentDefinition agent(String id, String name, String skillId, String description) {
        return agent(
                id,
                name,
                skillId,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                description);
    }

    static AgentDefinition agent(
            String id,
            String name,
            String skillId,
            String visibility,
            String status,
            String description) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentDefinition(
                id,
                name,
                "",
                description,
                List.of(),
                List.of(skillId),
                visibility,
                status,
                "test",
                now,
                now);
    }

    static List<AgentDefinition> builtinCatalog() {
        return List.of(
                agent("calc-agent", "薪酬计算专家", RoleSkills.CALCULATOR, "负责工资核算"),
                agent("val-agent", "薪酬复核专家", RoleSkills.VALIDATOR, "负责复核校验"),
                agent("acct-agent", "薪酬入账专家", RoleSkills.ACCOUNTANT, "负责入账汇总"));
    }

    static TeamIntent calculationIntent() {
        return intent(
                TeamIntent.TASK_CALCULATE,
                TeamIntent.CAP_PAYROLL_CALC,
                TeamIntent.CAP_PAYROLL_VALIDATE);
    }

    static TeamIntent intent(String taskType, String... capabilities) {
        return new TeamIntent(
                taskType,
                List.of("EMP001"),
                List.of(),
                List.of(),
                "2026-10",
                TeamIntent.RISK_MEDIUM,
                true,
                false,
                List.of(capabilities),
                0.9D,
                "test");
    }

    static AgentCandidate candidate(
            String agentId,
            TeamRole role,
            Set<String> capabilities,
            double rankScore,
            int samples,
            boolean coldStart,
            boolean builtin) {
        return new AgentCandidate(
                agentId,
                agentId,
                role,
                capabilities,
                1D,
                78D,
                65D,
                rankScore,
                samples,
                coldStart,
                AgentCapabilityProfile.LATENCY_NORMAL);
    }

    static ChatToolTrace trace(
            long id,
            long runId,
            String nodeId,
            String agent,
            String toolCallId,
            String netPay) {
        return new ChatToolTrace(
                id,
                0L,
                0L,
                toolCallId,
                "calculate_payroll",
                agent,
                runId,
                nodeId,
                "{\"employee_id\":\"EMP001\",\"period\":\"2026-10\"}",
                "{\"status\":\"OK\",\"data\":{\"net_pay\":" + netPay + "}}",
                false,
                "SUCCESS",
                42L,
                List.of("EMP001"),
                "2026-10",
                "研发部",
                Map.of("net_pay", new BigDecimal(netPay)),
                LocalDateTime.now());
    }
}
