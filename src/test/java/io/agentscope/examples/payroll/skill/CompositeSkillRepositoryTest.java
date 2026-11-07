package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompositeSkillRepositoryTest {

    @Test
    @DisplayName("Nacos 技能覆盖同名内置技能")
    void nacosWins() {
        NacosSkillRepository nacos =
                nacos(Map.of("payroll-calculator.md", "## 角色\nNacos 覆盖版本。"));
        CompositeSkillRepository composite =
                new CompositeSkillRepository(List.of(nacos, new BuiltInSkillRepository()));

        AgentSkill skill = composite.getSkill(RoleSkills.CALCULATOR);

        assertNotNull(skill);
        assertEquals(NacosSkillRepository.SOURCE, skill.getSource());
        assertTrue(skill.getSkillContent().contains("Nacos 覆盖版本"));
    }

    @Test
    @DisplayName("Nacos 缺少的技能回退内置版本，结果按名称去重")
    void fallsBackPerSkill() {
        NacosSkillRepository nacos =
                nacos(Map.of("payroll-calculator.md", "## 角色\nNacos 覆盖版本。"));
        CompositeSkillRepository composite =
                new CompositeSkillRepository(List.of(nacos, new BuiltInSkillRepository()));

        AgentSkill validator = composite.getSkill(RoleSkills.VALIDATOR);

        assertNotNull(validator);
        assertEquals(BuiltInSkillRepository.SOURCE, validator.getSource());
        List<AgentSkill> skills = composite.getAllSkills();
        assertEquals(RoleSkills.ids().size(), skills.size());
        assertEquals(
                RoleSkills.ids(),
                skills.stream().map(AgentSkill::getName).toList());
    }

    @Test
    @DisplayName("组合仓库只读并支持关闭全部来源")
    void readOnlyAndClosable() {
        CompositeSkillRepository composite =
                new CompositeSkillRepository(
                        List.of(nacos(Map.of()), new BuiltInSkillRepository()));

        assertFalse(composite.isWriteable());
        assertFalse(composite.save(List.of(), true));
        assertFalse(composite.delete(RoleSkills.CALCULATOR));
        assertEquals("composite", composite.getRepositoryInfo().getType());
        composite.close();
    }

    private static NacosSkillRepository nacos(Map<String, String> configs) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        true,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60));
        return new NacosSkillRepository(
                settings,
                dataId -> Optional.ofNullable(configs.get(dataId)),
                () -> 1_000L);
    }
}
