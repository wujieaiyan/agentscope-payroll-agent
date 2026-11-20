package io.agentscope.examples.payroll.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.devteam.DevContextBudget;
import io.agentscope.examples.payroll.devteam.DevEditSession;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevToolPolicy;
import io.agentscope.examples.payroll.devteam.DevWorkspaceContext;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final int MAX_FILE_SIZE_MB = 10;

    /** P0-3：研发域只读文件工具；未绑定编辑会话时写工具不会进入白名单。 */
    private static final Set<String> DEV_READ_TOOLS =
            Set.of("read_file", "grep_files", "glob_files", "list_files");

    /** P1-1：绑定编辑会话后追加的结构化写工具。 */
    private static final Set<String> DEV_WRITE_TOOLS = Set.of("write_file", "edit_file");

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
        return build(name, description, sysPrompt, model, toolkit, maxIters, skillRepository, null, null);
    }

    static HarnessAgent build(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            String devRole,
            Path projectRoot) {
        return build(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                devRole,
                projectRoot,
                null);
    }

    static HarnessAgent build(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            String devRole,
            Path projectRoot,
            DevContextBudget budget) {
        return build(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                devRole,
                projectRoot,
                budget,
                null);
    }

    /**
     * 研发角色按 {@link DevPathPolicy} 挂只读文件工具；绑定编辑会话时追加
     * {@code write_file}/{@code edit_file}，写入范围改为会话的隔离工作区。
     *
     * @param devRole 研发角色 id；{@code null} 表示非研发域
     * @param projectRoot 研发仓库根目录；为空时不挂文件工具（fail-closed）
     * @param budget 上下文预算；为空时启用工具结果淘汰但不启用压缩
     * @param editSession 任务编辑会话；非空时写工具生效且路径根切到沙箱工作区
     */
    static HarnessAgent build(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            String devRole,
            Path projectRoot,
            DevContextBudget budget,
            DevEditSession editSession) {
        return build(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                devRole,
                projectRoot,
                budget,
                editSession,
                null);
    }

    /** 携带任务级护栏记录（P0-6）：文件系统命中的拒绝写入运行器的 失败判定。 */
    static HarnessAgent build(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            String devRole,
            Path projectRoot,
            DevContextBudget budget,
            DevEditSession editSession,
            DevGuardMonitor guard) {
        Path effectiveRoot =
                editSession == null ? projectRoot : editSession.workspace().root();
        Set<String> expectedTools = new LinkedHashSet<>(toolkit.getToolNames());
        boolean devRead =
                devRole != null
                        && effectiveRoot != null
                        && DevPathPolicy.role(devRole)
                                .map(DevPathPolicy.RolePaths::readEnabled)
                                .orElse(false);
        Path workspace = workspace(name);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .agentId(identifier(name))
                        .name(name)
                        .description(description)
                        .sysPrompt(sysPrompt)
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(maxIters)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableShellTool()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableSessionPersistence()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableToolsConfig()
                        .disableDefaultWorkspaceSkills();
        if (devRole != null && effectiveRoot != null) {
            configureDevContext(builder, workspace, devRole, effectiveRoot, budget);
        } else {
            builder.disableWorkspaceContext().disableCompaction().disableToolResultEviction();
        }
        if (devRead) {
            AbstractFilesystem filesystem =
                    editSession == null
                            ? new DevGuardedFilesystem(
                                    devRole,
                                    effectiveRoot,
                                    new LocalFilesystem(
                                            effectiveRoot,
                                            LocalFsMode.ROOTED,
                                            PathPolicy.of(effectiveRoot),
                                            MAX_FILE_SIZE_MB,
                                            null),
                                    guard)
                            : new DevSessionFilesystem(
                                    devRole, effectiveRoot, editSession, guard);
            builder.abstractFilesystem(filesystem);
            expectedTools.addAll(DEV_READ_TOOLS);
            if (editSession != null) {
                expectedTools.addAll(DEV_WRITE_TOOLS);
            }
            log.info(
                    "[开发域] {} 文件工具已启用：范围={}，写模式={}，上下文预算={}",
                    devRole,
                    DevPathPolicy.describeScope(devRole),
                    editSession == null ? "只读" : "任务会话 " + editSession.taskId(),
                    budget == null
                            ? "未启用压缩"
                            : budget.compactionTriggerTokens() + " tokens 触发压缩");
        } else {
            builder.disableFilesystemTools();
        }
        builder.middleware(new UsageRecordingMiddleware());
        if (skillRepository != null) {
            builder.skillRepositories(List.of(skillRepository))
                    .middleware(new SkillToolGroupActivationMiddleware(skillRepository));
        }
        HarnessAgent agent = builder.build();
        removeHarnessOnlyTools(agent, expectedTools, devRole, editSession != null);
        return agent;
    }

    /**
     * 研发工作区上下文（P0-9）：把角色范围与仓库结构写进 harness 工作区并启用注入；
     * 工具结果超限时落盘淘汰，配置了编码模型时再开启压缩。
     */
    private static void configureDevContext(
            HarnessAgent.Builder builder,
            Path workspace,
            String devRole,
            Path projectRoot,
            DevContextBudget budget) {
        DevContextBudget effective = budget == null ? DevContextBudget.of(8_192, 1_024) : budget;
        String context = DevWorkspaceContext.render(devRole, projectRoot);
        try {
            Files.writeString(
                    workspace.resolve(DevWorkspaceContext.FILE_NAME),
                    context.isEmpty() ? "（无可注入的工作区上下文）" : context,
                    StandardCharsets.UTF_8);
            builder.additionalContextFile(DevWorkspaceContext.FILE_NAME);
        } catch (IOException e) {
            log.warn(
                    "[开发域] 写入工作区上下文失败，跳过注入：工作区={}，原因={}",
                    workspace,
                    e.getMessage());
        }
        builder.toolResultEviction(
                ToolResultEvictionConfig.builder()
                        .maxResultChars(effective.toolResultMaxChars())
                        .previewChars(effective.toolResultPreviewChars())
                        .build());
        if (budget == null) {
            builder.disableCompaction();
            return;
        }
        builder.maxContextTokens(budget.promptBudgetTokens())
                .compaction(
                        CompactionConfig.builder()
                                .triggerTokens(budget.compactionTriggerTokens())
                                .keepTokens(budget.compactionKeepTokens())
                                .triggerMessages(budget.compactionTriggerMessages())
                                .summaryPrompt(DevContextBudget.SUMMARY_PROMPT)
                                .build());
    }

    /**
     * harness 版本可能追加自己的工具（如异步等待、未来新增的平台工具）。模型只应看到
     * 业务工具与技能加载工具，否则小模型会被无关工具干扰。
     *
     * <p>研发角色改用 {@link DevToolPolicy} 作为白名单（P1-4）：未知工具、shell/终端/网络/
     * MCP 等一律移除，按 fail-closed 处理；非研发链路维持按业务工具集合裁剪。
     */
    private static void removeHarnessOnlyTools(
            HarnessAgent agent, Set<String> expectedTools, String devRole, boolean writableSession) {
        Toolkit live = agent.getToolkit();
        if (live == null) {
            return;
        }
        Set<String> removed = new LinkedHashSet<>();
        for (String toolName : Set.copyOf(live.getToolNames())) {
            boolean allowed =
                    SkillLoadTool.TOOL_NAME.equals(toolName)
                            || (devRole != null
                                    ? DevToolPolicy.allowed(devRole, toolName, writableSession)
                                    : expectedTools.contains(toolName));
            if (allowed) {
                continue;
            }
            live.removeTool(toolName);
            removed.add(toolName);
        }
        if (!removed.isEmpty()) {
            if (devRole != null) {
                log.warn("[开发域] {} 按工具策略移除未授权工具：{}", devRole, removed);
            } else {
                log.debug("[harness] 已移除与薪酬无关的默认工具：{}", removed);
            }
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
