package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 发布闸门黄金用例（S4）：内置角色全部放行，运行期必然失败的组合写时拒绝。 */
class AgentPublishGateTest {

    @Test
    @DisplayName("三个内置角色定义全部通过闸门")
    void builtinRolesPassGate() {
        for (String skillId : RoleSkills.ids()) {
            assertDoesNotThrow(
                    () -> AgentPublishGate.check(definition(AgentDefinition.STATUS_ONLINE, List.of(skillId))),
                    skillId);
        }
    }

    @Test
    @DisplayName("上线但未绑定技能被拒绝：运行期 boundSkills 必然抛错")
    void onlineWithoutSkillsRejected() {
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> AgentPublishGate.check(definition(AgentDefinition.STATUS_ONLINE, List.of())));

        assertTrue(error.getMessage().contains("必须绑定至少一个技能"), error.getMessage());
    }

    @Test
    @DisplayName("离线且不声明工具的定义允许保存草稿")
    void offlineDraftPasses() {
        assertDoesNotThrow(
                () -> AgentPublishGate.check(definition(AgentDefinition.STATUS_OFFLINE, List.of())));
    }

    @Test
    @DisplayName("声明业务工具却未绑定技能被拒绝：工具组没有激活入口")
    void declaredToolsRequireSkillBinding() {
        AgentDefinition definition =
                new AgentDefinition(
                        null,
                        "工具顾问",
                        "🤖",
                        "",
                        List.of(),
                        List.of(),
                        AgentDefinition.VISIBILITY_PUBLIC,
                        AgentDefinition.STATUS_OFFLINE,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("get_payroll_policy"),
                        io.agentscope.examples.payroll.model.AgentModelSettings.none());

        PayrollException error =
                assertThrows(PayrollException.class, () -> AgentPublishGate.check(definition));

        assertTrue(error.getMessage().contains("必须绑定技能"), error.getMessage());
    }

    private static AgentDefinition definition(String status, List<String> skillIds) {
        return new AgentDefinition(
                null,
                "测试智能体",
                "🤖",
                "",
                List.of(),
                skillIds,
                AgentDefinition.VISIBILITY_PUBLIC,
                status,
                null,
                null,
                null);
    }
}
