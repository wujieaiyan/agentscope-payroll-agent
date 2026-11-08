package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.skill.BuiltInSkillRepository;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 迁移到 HarnessAgent 后的技能加载、工具门禁与仓库越权边界。 */
class HarnessAgentWiringTest {

    private static final String SKILL_A = "skill-a";
    private static final String SKILL_B = "skill-b";
    private static final String SKILL_A_MARK = "ONLY-A-CONTENT-MARK";
    private static final String SKILL_B_MARK = "ONLY-B-CONTENT-MARK";

    @Test
    @DisplayName("harness 加载技能后启用业务工具，且不暴露 harness 默认工具")
    void harnessLoadingSkillActivatesBusinessTools() {
        PayrollTools tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
        AgentSkill skill = new BuiltInSkillRepository().getSkill(RoleSkills.CALCULATOR);
        Toolkit toolkit =
                PayrollAgentFactory.toolkit(
                        tools, Set.of("calculate_payroll"), "计算", skill);
        ScriptedSkillModel model = new ScriptedSkillModel(skill.getSkillId());
        AgentSkillRepository scoped =
                SkillRepositories.scoped(
                        new BuiltInSkillRepository(), List.of(RoleSkills.CALCULATOR));

        HarnessAgent agent =
                HarnessAgentSupport.build(
                        "PayrollCalculatorAgent",
                        "harness 迁移测试",
                        "基础提示词",
                        model,
                        toolkit,
                        4,
                        scoped);
        try {
            assertEquals(
                    Set.of("calculate_payroll", SkillLoadToolName.TOOL_NAME),
                    agent.getToolkit().getToolNames(),
                    "不应暴露 harness 默认的文件/记忆/异步工具");

            RuntimeContext context = session("user-1", "session-1");
            assertNotNull(agent.call("核算张三工资", context).block(Duration.ofSeconds(10)));

            assertTrue(model.systemText.contains("<available_skills>"), model.systemText);
            assertTrue(model.systemText.contains("基础提示词"), model.systemText);
            assertTrue(model.systemText.contains(RoleSkills.CALCULATOR), model.systemText);
            assertTrue(model.firstToolNames.contains(SkillLoadToolName.TOOL_NAME));
            assertFalse(model.firstToolNames.contains("calculate_payroll"));
            assertTrue(model.lastToolNames.contains("calculate_payroll"));
        } finally {
            agent.close();
        }
    }

    @Test
    @DisplayName("harness 限定仓库阻止跨角色加载技能")
    void harnessScopedRepositoryBlocksCrossRoleLoad() {
        RecordingModel model = new RecordingModel(SKILL_A + "_memory");
        AgentSkillRepository scoped =
                SkillRepositories.scoped(
                        new FixedSkillRepository(List.of(skillA(), skillB())),
                        List.of(SKILL_B));

        HarnessAgent agent =
                HarnessAgentSupport.build(
                        "ScopedHarnessAgent",
                        "仓库边界测试",
                        "基础提示词",
                        model,
                        new Toolkit(),
                        2,
                        scoped);
        try {
            RuntimeContext context = session("user-1", "session-b");
            agent.call("加载技能", context).block(Duration.ofSeconds(10));

            assertTrue(model.systemText.contains(SKILL_B + "_memory"), model.systemText);
            assertFalse(model.systemText.contains(SKILL_A + "_memory"), model.systemText);
            assertTrue(model.toolResultText.contains("Skill not found"), model.toolResultText);
            assertFalse(model.toolResultText.contains(SKILL_A_MARK), model.toolResultText);
        } finally {
            agent.close();
        }
    }

    private static RuntimeContext session(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }

    private static AgentSkill skillA() {
        return AgentSkill.builder()
                .name(SKILL_A)
                .description("技能 A")
                .skillContent("# A\n" + SKILL_A_MARK)
                .source("memory")
                .build();
    }

    private static AgentSkill skillB() {
        return AgentSkill.builder()
                .name(SKILL_B)
                .description("技能 B")
                .skillContent("# B\n" + SKILL_B_MARK)
                .source("memory")
                .build();
    }

    /** 避免直接依赖 harness 内部常量类名变化的包装。 */
    private static final class SkillLoadToolName {
        private static final String TOOL_NAME = "load_skill_through_path";
    }

    /** 第一次调用加载技能，之后返回文本；用于验证工具组在下一轮生效。 */
    private static final class ScriptedSkillModel extends ChatModelBase {

        private final String skillId;
        private volatile int calls;
        private volatile String systemText = "";
        private volatile List<String> firstToolNames = List.of();
        private volatile List<String> lastToolNames = List.of();

        private ScriptedSkillModel(String skillId) {
            this.skillId = skillId;
        }

        @Override
        public String getModelName() {
            return "scripted-harness-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            systemText =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.SYSTEM)
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            List<String> toolNames = tools.stream().map(ToolSchema::getName).toList();
            lastToolNames = toolNames;
            if (calls == 1) {
                firstToolNames = toolNames;
                Map<String, Object> arguments = Map.of("skillId", skillId, "path", "SKILL.md");
                ToolUseBlock loadSkill =
                        ToolUseBlock.builder()
                                .id("call-load-skill")
                                .name(SkillLoadToolName.TOOL_NAME)
                                .input(arguments)
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

    /** 第一轮加载未绑定的技能，第二轮收集工具结果文本。 */
    private static final class RecordingModel extends ChatModelBase {

        private final String loadSkillId;
        private volatile int calls;
        private volatile String systemText = "";
        private volatile String toolResultText = "";

        private RecordingModel(String loadSkillId) {
            this.loadSkillId = loadSkillId;
        }

        @Override
        public String getModelName() {
            return "recording-harness-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            systemText =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.SYSTEM)
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            if (calls == 1) {
                Map<String, Object> arguments =
                        Map.of("skillId", loadSkillId, "path", "SKILL.md");
                ToolUseBlock loadSkill =
                        ToolUseBlock.builder()
                                .id("call-cross-load")
                                .name(SkillLoadToolName.TOOL_NAME)
                                .input(arguments)
                                .content(
                                        "{\"skillId\":\""
                                                + loadSkillId
                                                + "\",\"path\":\"SKILL.md\"}")
                                .build();
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(loadSkill))
                                .finishReason("tool_calls")
                                .build());
            }
            StringBuilder text = new StringBuilder();
            for (Msg message : messages) {
                for (ContentBlock block : message.getContent()) {
                    if (block instanceof ToolResultBlock result) {
                        for (ContentBlock output : result.getOutput()) {
                            if (output instanceof TextBlock textBlock) {
                                text.append(textBlock.getText()).append('\n');
                            }
                        }
                    } else if (block instanceof TextBlock textBlock) {
                        text.append(textBlock.getText()).append('\n');
                    }
                }
            }
            toolResultText = text.toString();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("收到").build()))
                            .finishReason("stop")
                            .build());
        }
    }

    private static final class FixedSkillRepository implements AgentSkillRepository {

        private final List<AgentSkill> skills;

        private FixedSkillRepository(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream()
                    .filter(skill -> skill.getSkillId().equals(name) || skill.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            return false;
        }

        @Override
        public boolean delete(String name) {
            return false;
        }

        @Override
        public boolean skillExists(String name) {
            return getSkill(name) != null;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("memory", "test", false);
        }

        @Override
        public String getSource() {
            return "memory";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void close() {}
    }
}
