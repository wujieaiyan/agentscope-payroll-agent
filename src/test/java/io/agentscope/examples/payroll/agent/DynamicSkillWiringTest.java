package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.skill.BuiltInSkillRepository;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DynamicSkillWiringTest {

    @Test
    @DisplayName("动态技能把角色元数据写入系统提示并注册加载工具")
    void exposesSkillMetadataAndLoadTool() {
        CaptureModel model = new CaptureModel();
        Toolkit toolkit = new Toolkit();
        AgentSkillRepository repository = new BuiltInSkillRepository();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("PayrollCalculatorAgent")
                        .sysPrompt("基础提示词")
                        .model(model)
                        .toolkit(toolkit)
                        .skillRepository(repository)
                        .skillFilter(SkillRepositories.filterFor(RoleSkills.CALCULATOR))
                        .dynamicSkillsEnabled(true)
                        .maxIters(1)
                        .build();

        try {
            Msg reply = agent.call("你好", agent.getRuntimeContext()).block(Duration.ofSeconds(10));

            assertNotNull(reply);
            assertTrue(model.systemText.contains("<available_skills>"));
            assertTrue(model.systemText.contains("payroll-calculator_builtin"));
            assertTrue(model.toolNames.contains("load_skill_through_path"));
        } finally {
            agent.close();
        }
    }

    @Test
    @DisplayName("技能过滤器只放行对应角色")
    void filterOnlyAllowsRole() {
        assertTrue(SkillRepositories.filterFor(RoleSkills.CALCULATOR).isAllowed("payroll-calculator_builtin"));
        assertTrue(SkillRepositories.filterFor(RoleSkills.CALCULATOR).isAllowed("payroll-calculator_nacos"));
        assertTrue(!SkillRepositories.filterFor(RoleSkills.CALCULATOR).isAllowed("payroll-validator_builtin"));
    }

    @Test
    @DisplayName("多技能过滤器同时放行智能体绑定的全部角色")
    void filterAllowsAllBoundSkills() {
        var filter = SkillRepositories.filterFor(List.of(RoleSkills.CALCULATOR, RoleSkills.VALIDATOR));

        assertTrue(filter.isAllowed("payroll-calculator_builtin"));
        assertTrue(filter.isAllowed("payroll-validator_nacos"));
        assertFalse(filter.isAllowed("payroll-accountant_builtin"));
    }

    @Test
    @DisplayName("技能仓库视图只暴露智能体绑定的技能，避免加载未绑定角色")
    void scopedRepositoryOnlyExposesBoundSkills() {
        AgentSkillRepository repository =
                SkillRepositories.scoped(
                        new BuiltInSkillRepository(), List.of(RoleSkills.CALCULATOR));

        assertNotNull(repository.getSkill(RoleSkills.CALCULATOR));
        assertNull(repository.getSkill(RoleSkills.ACCOUNTANT));
        assertEquals(List.of(RoleSkills.CALCULATOR), repository.getAllSkillNames());
        assertEquals(
                List.of(RoleSkills.CALCULATOR),
                repository.getAllSkills().stream().map(AgentSkill::getName).toList());
    }

    @Test
    @DisplayName("自定义技能智能体的提示词不注入薪酬口径")
    void customSkillPromptHasNoPayrollFraming() {
        String prompt =
                PayrollAgentFactory.activeAgentPrompt(
                        agentDefinition(List.of("ai-coding")), "ai-coding_nacos");

        assertTrue(prompt.contains("ai-coding_nacos"));
        assertTrue(prompt.contains("测试智能体"));
        assertTrue(prompt.contains("绝对规则"));
        assertFalse(prompt.contains("薪酬"));
        assertFalse(prompt.contains("gross_pay"));
    }

    @Test
    @DisplayName("自定义技能智能体不继承薪酬工具白名单")
    void customSkillAgentHasNoPayrollTools() {
        assertTrue(PayrollAgentFactory.allowedTools(List.of("ai-coding")).isEmpty());
        assertFalse(PayrollAgentFactory.allowedTools(List.of(RoleSkills.CALCULATOR)).isEmpty());
        assertFalse(PayrollAgentFactory.allowedTools(List.of()).isEmpty());
    }

    @Test
    @DisplayName("绑定计算技能的智能体走三段流水线，其余按技能单兵作答")
    void pipelineDependsOnBoundSkills() {
        assertTrue(PayrollAgentFactory.isPipeline(null));
        assertTrue(
                PayrollAgentFactory.isPipeline(
                        agentDefinition(List.of(RoleSkills.CALCULATOR, RoleSkills.VALIDATOR))));
        assertFalse(
                PayrollAgentFactory.isPipeline(
                        agentDefinition(List.of(RoleSkills.VALIDATOR))));
        assertFalse(PayrollAgentFactory.isPipeline(agentDefinition(List.of())));
    }

    @Test
    @DisplayName("智能体绑定的技能不存在时直接报错，禁止回退薪酬计算技能")
    void missingSkillFailsLoudly() {
        AgentDefinition missing =
                agentDefinition(List.of("ai-coding-not-created"));
        PayrollException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        PayrollException.class,
                        () -> PayrollAgentFactory.boundSkills(
                                new BuiltInSkillRepository(), missing));
        assertTrue(error.getMessage().contains("绑定的技能不存在"), error.getMessage());
        assertTrue(error.getMessage().contains("已阻止回退"), error.getMessage());

        AgentDefinition empty = agentDefinition(List.of());
        PayrollException emptyError =
                org.junit.jupiter.api.Assertions.assertThrows(
                        PayrollException.class,
                        () -> PayrollAgentFactory.boundSkills(
                                new BuiltInSkillRepository(), empty));
        assertTrue(emptyError.getMessage().contains("未绑定技能"), emptyError.getMessage());
    }

    private static AgentDefinition agentDefinition(List<String> skillIds) {
        return new AgentDefinition(
                "agent-test",
                "测试智能体",
                "🤖",
                "测试用",
                List.of(),
                skillIds,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "system",
                null,
                null);
    }

    @Test
    @DisplayName("业务工具组按技能裸名称注册，加载技能后启用")
    void businessToolsGatedBySkill() {
        PayrollTools tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
        AgentSkill nacosSkill =
                AgentSkill.builder()
                        .name(RoleSkills.CALCULATOR)
                        .description("测试用 Nacos 技能")
                        .skillContent("## 角色\n测试")
                        .source("nacos")
                        .build();

        Toolkit toolkit =
                PayrollAgentFactory.toolkit(
                        tools, Set.of("calculate_payroll", "generate_payslip"), "计算", nacosSkill);

        // AgentScope 激活工具组时用 AgentSkill.getName()，不能用带来源后缀的 skillId。
        assertTrue(
                toolkit.findSkillToolGroupsByActivateOnSkill(RoleSkills.CALCULATOR)
                        .contains("role-tools"));
        assertTrue(
                toolkit.findSkillToolGroupsByActivateOnSkill(nacosSkill.getSkillId()).isEmpty());
        assertTrue(
                toolkit.getToolSchemas().stream()
                        .noneMatch(schema -> schema.getName().equals("calculate_payroll")));

        toolkit.updateToolGroups(List.of("role-tools"), true);

        assertTrue(
                toolkit.getToolSchemas().stream()
                        .anyMatch(schema -> schema.getName().equals("calculate_payroll")));
    }

    @Test
    @DisplayName("模型调用 load_skill_through_path 后业务工具自动出现")
    void loadingSkillActivatesBusinessTools() {
        PayrollTools tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
        BuiltInSkillRepository repository = new BuiltInSkillRepository();
        AgentSkill skill = repository.getSkill(RoleSkills.CALCULATOR);
        Toolkit toolkit =
                PayrollAgentFactory.toolkit(
                        tools, Set.of("calculate_payroll"), "计算", skill);
        ScriptedSkillModel model = new ScriptedSkillModel(skill.getSkillId());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("PayrollCalculatorAgent")
                        .sysPrompt("基础提示词")
                        .model(model)
                        .toolkit(toolkit)
                        .skillRepository(repository)
                        .skillFilter(SkillRepositories.filterFor(RoleSkills.CALCULATOR))
                        .dynamicSkillsEnabled(true)
                        .maxIters(4)
                        .build();
        try {
            assertNotNull(agent.call("核算张三工资", agent.getRuntimeContext())
                    .block(Duration.ofSeconds(10)));

            assertTrue(model.calls >= 2);
            assertTrue(model.firstToolNames.contains("load_skill_through_path"));
            assertFalse(model.firstToolNames.contains("calculate_payroll"));
            assertTrue(model.lastToolNames.contains("calculate_payroll"));
        } finally {
            agent.close();
        }
    }

    private static final class CaptureModel extends ChatModelBase {

        private volatile String systemText = "";
        private volatile List<String> toolNames = List.of();

        @Override
        public String getModelName() {
            return "capture-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            toolNames = tools.stream().map(ToolSchema::getName).toList();
            systemText =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.SYSTEM)
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            List<ContentBlock> content =
                    List.of(TextBlock.builder().text("收到").build());
            return Flux.just(
                    ChatResponse.builder().content(content).finishReason("stop").build());
        }
    }

    /** 第一次调用返回一个加载技能的 tool call，之后返回文本，用于验证工具组自动激活。 */
    private static final class ScriptedSkillModel extends ChatModelBase {

        private final String skillId;
        private volatile int calls;
        private volatile List<String> firstToolNames = List.of();
        private volatile List<String> lastToolNames = List.of();

        private ScriptedSkillModel(String skillId) {
            this.skillId = skillId;
        }

        @Override
        public String getModelName() {
            return "scripted-skill-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            List<String> toolNames = tools.stream().map(ToolSchema::getName).toList();
            lastToolNames = toolNames;
            if (calls == 1) {
                firstToolNames = toolNames;
                Map<String, Object> arguments = Map.of("skillId", skillId, "path", "SKILL.md");
                ToolUseBlock loadSkill =
                        ToolUseBlock.builder()
                                .id("call-load-skill")
                                .name("load_skill_through_path")
                                .input(arguments)
                                // 工具执行前的参数校验读取的是原始 JSON，而不是 input map。
                                .content("{\"skillId\":\"" + skillId + "\",\"path\":\"SKILL.md\"}")
                                .build();
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(loadSkill))
                                .finishReason("tool_calls")
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("已加载角色技能").build()))
                            .finishReason("stop")
                            .build());
        }
    }
}
