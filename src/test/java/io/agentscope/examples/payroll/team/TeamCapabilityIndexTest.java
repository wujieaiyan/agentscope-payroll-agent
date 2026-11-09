package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamCapabilityIndexTest {

    @Test
    @DisplayName("内置角色技能映射为可担任强制角色的能力画像")
    void mapsBuiltinSkillsToCapabilities() {
        CapabilityIndex index = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());

        AgentCapabilityProfile calculator = index.profile("calc-agent");
        assertNotNull(calculator);
        assertEquals(AgentCapabilityProfile.SOURCE_BUILTIN, calculator.source());
        assertTrue(calculator.canBeMandatoryRole());
        assertTrue(calculator.supports(TeamIntent.CAP_PAYROLL_CALC));
        assertTrue(calculator.supports(TeamIntent.CAP_PAYROLL_QUERY));
        assertEquals(AgentCapabilityProfile.LATENCY_NORMAL, calculator.latencyClass());
        assertTrue(calculator.supportedTaskTypes().contains(TeamIntent.TASK_CALCULATE));
        assertTrue(index.skillsUsable(index.definition("calc-agent")));

        AgentCapabilityProfile validator = index.profile("val-agent");
        assertTrue(validator.supports(TeamIntent.CAP_PAYROLL_VALIDATE));
        assertEquals(AgentCapabilityProfile.LATENCY_NORMAL, validator.latencyClass());

        AgentCapabilityProfile accountant = index.profile("acct-agent");
        assertTrue(accountant.supports(TeamIntent.CAP_ACCOUNTING));
        assertTrue(accountant.supports(TeamIntent.CAP_PAYROLL_QUERY));
    }

    @Test
    @DisplayName("未知技能按描述关键词推断能力，但只能担任辅助位")
    void infersCapabilitiesFromKeywords() {
        AgentDefinition custom =
                TeamTestFixtures.agent(
                        "custom-agent", "政策助手", "custom-policy-skill", "负责政策、规则与解释");
        CapabilityIndex index = CapabilityIndex.from(List.of(custom));

        AgentCapabilityProfile profile = index.profile("custom-agent");
        assertEquals(AgentCapabilityProfile.SOURCE_INFERRED, profile.source());
        assertFalse(profile.canBeMandatoryRole());
        assertTrue(profile.supports(TeamIntent.CAP_POLICY_KNOWLEDGE));
        assertEquals(AgentCapabilityProfile.LATENCY_FAST, profile.latencyClass());
        assertTrue(profile.supportedTaskTypes().contains(TeamIntent.TASK_POLICY_EXPLAIN));
        assertFalse(index.skillsUsable(custom));
    }

    @Test
    @DisplayName("内置技能 id 可直接解析，无技能或未知技能不可用")
    void resolvesSkillsUsable() {
        CapabilityIndex index = CapabilityIndex.from(TeamTestFixtures.builtinCatalog());
        assertTrue(index.skillsUsable(index.definition("calc-agent")));
        assertFalse(index.skillsUsable(new AgentDefinition(
                "no-skill", "无技能", "", "", List.of(), List.of(), "PUBLIC", "ONLINE", "test", null, null)));
        assertFalse(index.skillsUsable(null));
        assertTrue(RoleSkills.ids().contains("payroll-calculator"));
    }
}
