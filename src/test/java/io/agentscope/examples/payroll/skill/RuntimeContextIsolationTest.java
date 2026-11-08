package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * AgentScope 2.0.1「单实例 + RuntimeContext」可行性验证。
 *
 * <p>结论（实测）：
 *
 * <ol>
 *   <li>会话历史按 {@code (userId, sessionId)} 隔离，并发调用不会串话；
 *   <li>{@code SkillFilter} 放在 {@code RuntimeContext} 里可按会话过滤系统提示的技能清单；
 *   <li>但 {@code SkillBox} 是智能体级状态，{@code load_skill_through_path} 只认 SkillBox，
 *       不校验上下文 filter——同一个智能体注册了 A、B 两个技能时，只允许 B 的会话仍能读 A。
 * </ol>
 *
 * <p>因此共享智能体的安全前提是「一个智能体定义一个实例，并各自使用只含绑定技能的仓库」
 * （{@link #scopedRepositoryBlocksCrossSkillLoad()}），不能用一个全局实例靠 filter 隔离。
 *
 * <p>{@link #harnessCatalogBlocksCrossSkillLoad()} 另外验证了 harness 的替代路径：技能目录
 * 按 RuntimeContext 存放、load 工具只读当前上下文目录，因此可以在同一实例上注册 A/B 而
 * 不越权；其 SkillFilter 按技能名匹配，与 core 按 skillId 匹配不同。
 */
class RuntimeContextIsolationTest {

    private static final String SKILL_A = "skill-a";
    private static final String SKILL_B = "skill-b";
    private static final String SKILL_A_ID = SKILL_A + "_memory";
    private static final String SKILL_B_ID = SKILL_B + "_memory";
    private static final String SKILL_A_MARK = "ONLY-A-CONTENT-MARK";
    private static final String SKILL_B_MARK = "ONLY-B-CONTENT-MARK";

    @Test
    @DisplayName("单实例并发两会话：历史与提示词按上下文隔离，但 load 工具仍是智能体级")
    void promptAndHistoryAreScopedByRuntimeContext() throws Exception {
        RecordingModel model = new RecordingModel();
        Toolkit toolkit = new Toolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("spike")
                        .sysPrompt("BASE")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(2)
                        .skillRepository(repository(skillA(), skillB()))
                        .dynamicSkillsEnabled(true)
                        .build();

        RuntimeContext sessionA = session("user-1", "session-a", SKILL_A_ID);
        RuntimeContext sessionB = session("user-2", "session-b", SKILL_B_ID);

        CompletableFuture<Void> callA =
                CompletableFuture.runAsync(
                        () ->
                                agent.call(List.of(user("session-a 的问题")), sessionA)
                                        .block(Duration.ofSeconds(30)));
        CompletableFuture<Void> callB =
                CompletableFuture.runAsync(
                        () ->
                                agent.call(List.of(user("session-b 的问题")), sessionB)
                                        .block(Duration.ofSeconds(30)));
        CompletableFuture.allOf(callA, callB).get();

        List<List<Msg>> promptsA = model.promptsFor("session-a 的问题");
        List<List<Msg>> promptsB = model.promptsFor("session-b 的问题");
        assertFalse(promptsA.isEmpty(), "会话 A 未触发模型调用");
        assertFalse(promptsB.isEmpty(), "会话 B 未触发模型调用");

        String systemA = systemText(promptsA.get(0));
        String systemB = systemText(promptsB.get(0));
        assertTrue(systemA.contains(SKILL_A_ID), "会话 A 应看到技能 A：" + systemA);
        assertFalse(systemA.contains(SKILL_B_ID), "会话 A 不应看到技能 B：" + systemA);
        assertTrue(systemB.contains(SKILL_B_ID), "会话 B 应看到技能 B：" + systemB);
        assertFalse(systemB.contains(SKILL_A_ID), "会话 B 不应看到技能 A：" + systemB);

        assertFalse(
                allText(promptsB.get(0)).contains("session-a 的问题"),
                "会话 B 不应看到会话 A 的历史");
        assertFalse(
                allText(promptsA.get(0)).contains("session-b 的问题"),
                "会话 A 不应看到会话 B 的历史");

        // 已知缺陷：SkillBox 是智能体级状态，load 工具不校验 RuntimeContext 里的 SkillFilter。
        ToolResultBlock crossLoad = load(agent, sessionB, SKILL_A_ID);
        String crossText = textOf(crossLoad.getOutput());
        assertTrue(
                crossText.contains(SKILL_A_MARK),
                "2.0.1 的 load 工具应绕过上下文 filter，直接命中共享 SkillBox");
    }

    @Test
    @DisplayName("按定义隔离仓库后，共享实例无法跨角色加载技能")
    void scopedRepositoryBlocksCrossSkillLoad() {
        RecordingModel model = new RecordingModel();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("spike-b")
                        .sysPrompt("BASE")
                        .model(model)
                        .toolkit(new Toolkit())
                        .maxIters(2)
                        .skillRepository(repository(skillB()))
                        .dynamicSkillsEnabled(true)
                        .build();

        RuntimeContext sessionB = session("user-2", "session-b", SKILL_B_ID);
        agent.call(List.of(user("session-b 的问题")), sessionB).block(Duration.ofSeconds(30));

        List<List<Msg>> prompts = model.promptsFor("session-b 的问题");
        assertFalse(prompts.isEmpty(), "会话 B 未触发模型调用");
        String system = systemText(prompts.get(0));
        assertTrue(system.contains(SKILL_B_ID), "应看到技能 B：" + system);
        assertFalse(system.contains(SKILL_A_ID), "仓库只含 B，不应出现技能 A：" + system);

        ToolResultBlock crossLoad = load(agent, sessionB, SKILL_A_ID);
        String crossText = textOf(crossLoad.getOutput());
        assertFalse(crossText.contains(SKILL_A_MARK), "限定仓库仍被越权读取：" + crossText);
    }

    @Test
    @DisplayName("harness 上下文目录：同一实例注册 A/B，B 会话无法加载 A")
    void harnessCatalogBlocksCrossSkillLoad() {
        RecordingModel model = new RecordingModel();
        Toolkit toolkit = new Toolkit();
        HarnessSkillMiddleware middleware =
                HarnessSkillMiddleware.frozen(
                        List.of(repository(skillA(), skillB())),
                        toolkit,
                        SkillFilter.all(),
                        null,
                        null,
                        ShellPathPolicy.noShell());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("spike-harness")
                        .sysPrompt("BASE")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(2)
                        .middleware(middleware)
                        .build();

        // harness 的 SkillFilter 按技能名（getName）过滤，core 的 SkillBox 按 skillId 过滤。
        RuntimeContext sessionA = sessionByName("user-1", "session-a", SKILL_A);
        RuntimeContext sessionB = sessionByName("user-2", "session-b", SKILL_B);
        agent.call(List.of(user("session-a 的问题")), sessionA).block(Duration.ofSeconds(30));
        agent.call(List.of(user("session-b 的问题")), sessionB).block(Duration.ofSeconds(30));

        List<List<Msg>> promptsA = model.promptsFor("session-a 的问题");
        List<List<Msg>> promptsB = model.promptsFor("session-b 的问题");
        assertFalse(promptsA.isEmpty(), "会话 A 未触发模型调用");
        assertFalse(promptsB.isEmpty(), "会话 B 未触发模型调用");
        String systemA = systemText(promptsA.get(0));
        String systemB = systemText(promptsB.get(0));
        assertTrue(systemA.contains(SKILL_A_ID), "会话 A 应看到技能 A：" + systemA);
        assertFalse(systemA.contains(SKILL_B_ID), "会话 A 不应看到技能 B：" + systemA);
        assertTrue(systemB.contains(SKILL_B_ID), "会话 B 应看到技能 B：" + systemB);
        assertFalse(systemB.contains(SKILL_A_ID), "会话 B 不应看到技能 A：" + systemB);

        ToolResultBlock crossLoad = load(agent, sessionB, SKILL_A_ID);
        String crossText = textOf(crossLoad.getOutput());
        assertFalse(crossText.contains(SKILL_A_MARK), "harness 目录仍被越权读取：" + crossText);
    }

    private static RuntimeContext session(String user, String session, String skillId) {
        return RuntimeContext.builder()
                .userId(user)
                .sessionId(session)
                .put(SkillFilter.class, SkillFilter.only(skillId))
                .build();
    }

    private static RuntimeContext sessionByName(String user, String session, String skillName) {
        return RuntimeContext.builder()
                .userId(user)
                .sessionId(session)
                .put(SkillFilter.class, SkillFilter.only(skillName))
                .build();
    }

    private static ToolResultBlock load(ReActAgent agent, RuntimeContext context, String skillId) {
        Toolkit toolkit = agent.getToolkit();
        assertNotNull(toolkit, "智能体未暴露 toolkit");
        AgentTool loadTool = toolkit.getTool("load_skill_through_path");
        assertNotNull(loadTool, "动态技能未注册 load_skill_through_path");
        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolResultBlock result =
                loadTool.callAsync(
                                ToolCallParam.builder()
                                        .agent(agent)
                                        .runtimeContext(context)
                                        .toolUseBlock(
                                                new ToolUseBlock(
                                                        "spike-call",
                                                        "load_skill_through_path",
                                                        input))
                                        .input(input)
                                        .build())
                        .block(Duration.ofSeconds(10));
        assertNotNull(result, "加载技能没有返回结果");
        return result;
    }

    private static Msg user(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }

    private static String systemText(List<Msg> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.SYSTEM)
                .map(message -> textOf(message.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private static String allText(List<Msg> messages) {
        return messages.stream()
                .map(message -> textOf(message.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private static String textOf(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static AgentSkill skillA() {
        return AgentSkill.builder()
                .name(SKILL_A)
                .description("技能 A 角色")
                .skillContent("# A\n" + SKILL_A_MARK)
                .source("memory")
                .build();
    }

    private static AgentSkill skillB() {
        return AgentSkill.builder()
                .name(SKILL_B)
                .description("技能 B 角色")
                .skillContent("# B\n" + SKILL_B_MARK)
                .source("memory")
                .build();
    }

    private static FixedSkillRepository repository(AgentSkill... skills) {
        return new FixedSkillRepository(List.of(skills));
    }

    private static final class RecordingModel implements Model {

        private final List<List<Msg>> calls = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .id("response-" + calls.size())
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("已收到：" + lastUserText(messages))
                                                    .build()))
                            .finishReason("stop")
                            .build());
        }

        List<List<Msg>> promptsFor(String question) {
            return calls.stream()
                    .filter(messages -> allText(messages).contains(question))
                    .toList();
        }

        private static String lastUserText(List<Msg> messages) {
            return messages.stream()
                    .filter(message -> message.getRole() == MsgRole.USER)
                    .reduce((first, second) -> second)
                    .map(message -> textOf(message.getContent()))
                    .orElse("");
        }

        @Override
        public String getModelName() {
            return "spike-model";
        }
    }

    private static final class FixedSkillRepository implements AgentSkillRepository {

        private final List<AgentSkill> skills;

        private FixedSkillRepository(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(skill -> skill.getName().equals(name)).findFirst()
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
