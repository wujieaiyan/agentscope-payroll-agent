package io.agentscope.examples.payroll.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以最小 harness 能力集构建运行期智能体。
 *
 * <p>薪酬应用只需要「ReAct + 上下文级技能目录 + 业务工具」：记忆、压缩、转录、工作区上下文、
 * 文件/Shell、子智能体、tools.json 都在这里关闭，避免额外 LLM 调用与文件访问面。
 * 会话状态使用 {@link InMemoryAgentStateStore}，隔离仍由显式 {@code RuntimeContext} 保证。
 */
final class HarnessAgentSupport {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentSupport.class);
    private static final String WORKSPACE_PROPERTY = "payroll.harness.workspace";

    private HarnessAgentSupport() {
    }

    static HarnessAgent build(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository) {
        Set<String> expectedTools = new LinkedHashSet<>(toolkit.getToolNames());
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .agentId(identifier(name))
                        .name(name)
                        .description(description)
                        .sysPrompt(sysPrompt)
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(maxIters)
                        .workspace(workspace(name))
                        .stateStore(new InMemoryAgentStateStore())
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableSessionPersistence()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableToolsConfig()
                        .disableDefaultWorkspaceSkills()
                        .disableCompaction()
                        .disableToolResultEviction();
        if (skillRepository != null) {
            builder.skillRepositories(List.of(skillRepository))
                    .middleware(new SkillToolGroupActivationMiddleware(skillRepository));
        }
        HarnessAgent agent = builder.build();
        removeHarnessOnlyTools(agent, expectedTools);
        return agent;
    }

    /**
     * harness 版本可能追加自己的工具（如异步等待、未来新增的平台工具）。模型只应看到
     * 业务工具与技能加载工具，否则小模型会被无关工具干扰。
     */
    private static void removeHarnessOnlyTools(HarnessAgent agent, Set<String> expectedTools) {
        Toolkit live = agent.getToolkit();
        if (live == null) {
            return;
        }
        Set<String> removed = new LinkedHashSet<>();
        for (String toolName : Set.copyOf(live.getToolNames())) {
            if (SkillLoadTool.TOOL_NAME.equals(toolName) || expectedTools.contains(toolName)) {
                continue;
            }
            live.removeTool(toolName);
            removed.add(toolName);
        }
        if (!removed.isEmpty()) {
            log.debug("[harness] 已移除与薪酬无关的默认工具：{}", removed);
        }
    }

    private static Path workspace(String agentName) {
        String root = System.getProperty(WORKSPACE_PROPERTY);
        Path base =
                root == null || root.isBlank()
                        ? Path.of(System.getProperty("java.io.tmpdir"), "payroll-harness")
                        : Path.of(root);
        Path workspace = base.resolve(identifier(agentName));
        try {
            return Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new PayrollException(
                    "无法创建 harness 工作区：" + workspace + "，原因：" + e.getMessage(), e);
        }
    }

    private static String identifier(String name) {
        String value = name == null || name.isBlank() ? "agent" : name.trim();
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
