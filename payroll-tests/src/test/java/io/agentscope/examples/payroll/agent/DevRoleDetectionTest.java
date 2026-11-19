package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 研发域角色识别：优先智能体 id，其次绑定技能 id；薪酬与普通智能体不受影响。 */
class DevRoleDetectionTest {

    @TempDir Path projectRoot;

    @Test
    @DisplayName("智能体 id 命中九角色时识别为研发角色")
    void detectsByAgentId() {
        assertEquals(
                Optional.of(DevPathPolicy.ROLE_IMPLEMENTER),
                PayrollAgentFactory.devRole(agent("project-implementer", List.of())));
        assertEquals(
                Optional.of(DevPathPolicy.ROLE_REVIEWER),
                PayrollAgentFactory.devRole(agent("Project-Reviewer", List.of())));
    }

    @Test
    @DisplayName("id 未命中时按绑定技能识别，忽略其他技能")
    void detectsBySkillId() {
        assertEquals(
                Optional.of(DevPathPolicy.ROLE_FRONTEND),
                PayrollAgentFactory.devRole(
                        agent("custom-agent", List.of("poetry", DevPathPolicy.ROLE_FRONTEND))));
    }

    @Test
    @DisplayName("薪酬、自定义与空定义不识别为研发角色")
    void ignoresNonDevAgents() {
        assertTrue(
                PayrollAgentFactory.devRole(agent("payroll-calculator", List.of("poetry"))).isEmpty());
        assertTrue(PayrollAgentFactory.devRole(null).isEmpty());
        assertFalse(PayrollAgentFactory.devRole(agent("custom-agent", null)).isPresent());
    }

    @Test
    @DisplayName("研发角色只挂只读文件工具，产品经理与非研发角色不挂")
    void readOnlyFilesystemToolsForDevRoles() {
        HarnessAgent implementer =
                HarnessAgentSupport.build(
                        "DevImplementer",
                        "研发域后端开发",
                        "提示词",
                        stubModel(),
                        new Toolkit(),
                        4,
                        null,
                        DevPathPolicy.ROLE_IMPLEMENTER,
                        projectRoot);
        Set<String> tools = implementer.getToolkit().getToolNames();
        assertTrue(tools.containsAll(Set.of("read_file", "grep_files", "glob_files", "list_files")), tools.toString());
        assertFalse(tools.contains("write_file"), tools.toString());
        assertFalse(tools.contains("edit_file"), tools.toString());

        HarnessAgent product =
                HarnessAgentSupport.build(
                        "DevProduct",
                        "研发域产品经理",
                        "提示词",
                        stubModel(),
                        new Toolkit(),
                        4,
                        null,
                        DevPathPolicy.ROLE_PRODUCT,
                        projectRoot);
        assertTrue(product.getToolkit().getToolNames().isEmpty(), product.getToolkit().getToolNames().toString());
    }

    private static ChatModelBase stubModel() {
        return new ChatModelBase() {
            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
    }

    private static AgentDefinition agent(String id, List<String> skillIds) {
        return new AgentDefinition(
                id,
                id,
                "",
                "测试智能体",
                List.of(),
                skillIds,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null);
    }
}
