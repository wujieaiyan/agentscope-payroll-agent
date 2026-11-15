package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillExecutionsTest {

    @Test
    @DisplayName("识别嵌套与拍平两种执行开关写法")
    void readsBothMetadataShapes() {
        assertTrue(SkillExecutions.executionEnabled(Map.of("execution", Map.of("enabled", true))));
        assertTrue(SkillExecutions.executionEnabled(Map.of("execution.enabled", true)));
        assertTrue(SkillExecutions.executionEnabled(Map.of("execution.enabled", " TRUE ")));
        assertFalse(SkillExecutions.executionEnabled(Map.of("execution", Map.of("enabled", false))));
        assertFalse(SkillExecutions.executionEnabled(Map.of("execution.enabled", "no")));
        assertFalse(SkillExecutions.executionEnabled(Map.of("execution", "true")));
        assertFalse(SkillExecutions.executionEnabled(Map.of()));
        assertFalse(SkillExecutions.executionEnabled((Map<String, ?>) null));
    }

    @Test
    @DisplayName("技能对象与空值处理")
    void readsFromSkill() {
        AgentSkill enabled =
                AgentSkill.builder()
                        .name("payroll-auditor")
                        .description("auditor")
                        .skillContent("## 角色\n测试。")
                        .putMetadata("execution", Map.of("enabled", true))
                        .build();
        AgentSkill plain =
                AgentSkill.builder()
                        .name("payroll-auditor")
                        .description("auditor")
                        .skillContent("## 角色\n测试。")
                        .build();

        assertTrue(SkillExecutions.executionEnabled(enabled));
        assertFalse(SkillExecutions.executionEnabled(plain));
        assertFalse(SkillExecutions.executionEnabled((AgentSkill) null));
    }
}
