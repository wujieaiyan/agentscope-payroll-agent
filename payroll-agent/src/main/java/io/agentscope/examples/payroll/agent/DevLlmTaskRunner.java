package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.devteam.DevArtifact;
import io.agentscope.examples.payroll.devteam.DevArtifactType;
import io.agentscope.examples.payroll.devteam.DevBuildResult;
import io.agentscope.examples.payroll.devteam.DevBuildRunner;
import io.agentscope.examples.payroll.devteam.DevEditSession;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevReviewReport;
import io.agentscope.examples.payroll.devteam.DevTask;
import io.agentscope.examples.payroll.devteam.DevTaskRun;
import io.agentscope.examples.payroll.devteam.DevTaskRunner;
import io.agentscope.examples.payroll.devteam.DevTeamSettings;
import io.agentscope.examples.payroll.devteam.DevWorkspace;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研发任务的默认运行器（P1-2）：克隆隔离工作区 → 按角色构建智能体 → 执行 → 快照补丁。
 *
 * <p>每个任务使用独立的工作区目录，结束即删除；写角色只有开发域启用写模式时才允许执行，
 * 否则直接失败而不是降级到控制面模型。模型执行结束且未触发护栏后，按路径 profile 声明的
 * 命令在容器内做构建校验（P1-3），校验失败不产出补丁。任务结果与补丁版本写回
 * {@link DevArtifactStore}，供 G2 批量审批读取。
 */
public final class DevLlmTaskRunner implements DevTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(DevLlmTaskRunner.class);
    private static final int MAX_SUMMARY_CHARS = 400;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_REVIEW_INPUT_CHARS = 24_000;

    private final DevTeamSettings settings;
    private final DevArtifactStore artifacts;
    private final Path projectRoot;
    private final Path workRoot;
    private final AuditService audit;
    private final DevBuildRunner buildRunner;

    public DevLlmTaskRunner(
            DevTeamSettings settings,
            DevArtifactStore artifacts,
            Path projectRoot,
            Path workRoot,
            AuditService audit,
            DevBuildRunner buildRunner) {
        if (settings == null) {
            throw new IllegalArgumentException("DevTeamSettings 不能为空");
        }
        if (artifacts == null) {
            throw new IllegalArgumentException("DevArtifactStore 不能为空");
        }
        if (projectRoot == null) {
            throw new IllegalArgumentException("研发仓库根目录不能为空");
        }
        if (workRoot == null) {
            throw new IllegalArgumentException("研发工作区根目录不能为空");
        }
        if (buildRunner == null) {
            throw new IllegalArgumentException("构建校验执行器不能为空");
        }
        this.settings = settings;
        this.artifacts = artifacts;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.workRoot = workRoot.toAbsolutePath().normalize();
        this.audit = audit;
        this.buildRunner = buildRunner;
    }

    @Override
    public String run(DevTaskRun run) throws Exception {
        DevTask task = run.task();
        DevPathPolicy.RolePaths paths =
                DevPathPolicy.role(task.role())
                        .orElseThrow(() -> new PayrollException("未知研发角色：" + task.role()));
        boolean writable = paths.writable();
        if (writable && !settings.writeEnabled()) {
            String reason =
                    settings.disabledReason().isBlank()
                            ? "开发域未启用写模式"
                            : settings.disabledReason();
            throw new PayrollException("无法执行写代码任务：" + reason);
        }
        if (!Files.isDirectory(projectRoot.resolve(".git"))) {
            throw new PayrollException("研发仓库根目录不是 Git 仓库：" + projectRoot);
        }
        DevArtifactType patchType = DevArtifactType.patchForRole(paths.role()).orElse(null);
        String owner = task.ownerAgent().isBlank() ? paths.role() : task.ownerAgent();
        Path runDir =
                workRoot.resolve(
                        safe(run.planId())
                                + "-"
                                + safe(task.taskId())
                                + "-a"
                                + task.attempt()
                                + "-"
                                + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(workRoot);
        run.checkpoint();
        try (DevWorkspace workspace = DevWorkspace.clone(projectRoot, runDir)) {
            DevEditSession session =
                    writable && patchType != null
                            ? new DevEditSession(
                                    task.taskId(),
                                    patchType,
                                    owner,
                                    paths.role(),
                                    workspace,
                                    artifacts,
                                    audit)
                            : null;
            return execute(
                    run,
                    task,
                    paths.role(),
                    owner,
                    session,
                    new DevGuardMonitor(),
                    workspace.root());
        }
    }

    private String execute(
            DevTaskRun run,
            DevTask task,
            String role,
            String owner,
            DevEditSession session,
            DevGuardMonitor guard,
            Path workspaceRoot)
            throws Exception {
        HarnessAgent agent = PayrollAgentFactory.createDevTaskAgent(role, owner, session, guard);
        try {
            run.onCancel(agent::interrupt);
            run.checkpoint();
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId(owner)
                            .sessionId("devtask-" + run.planId() + "-" + task.taskId())
                            .build();
            Duration timeout =
                    Duration.ofSeconds(Math.max(MIN_TIMEOUT_SECONDS, settings.model().timeoutSeconds()));
            String prompt =
                    DevPathPolicy.ROLE_REVIEWER.equalsIgnoreCase(role)
                            ? taskPrompt(task) + reviewInput(task, artifacts)
                            : taskPrompt(task);
            Msg reply = agent.call(prompt, context).block(timeout);
            auditUsage(audit, task.taskId(), role, reply, settings);
            run.checkpoint();
            if (guard.breached()) {
                auditGuardBreach(audit, task.taskId(), role, guard);
                log.warn(
                        "[开发域] 任务触发护栏，按 fail-closed 失败：task={}，事件={}",
                        task.taskId(),
                        guard.events());
                throw new PayrollException("护栏触发，任务停止：" + guard.summary());
            }
            String builds = verifyBuilds(task, role, workspaceRoot);
            if (DevPathPolicy.ROLE_REVIEWER.equalsIgnoreCase(role)) {
                return recordReview(task, reply, builds);
            }
            String summary = replySummary(reply);
            if (session != null && !session.touchedFiles().isEmpty()) {
                DevArtifact artifact =
                        session.snapshot(task.title() + "（第 " + (task.attempt() + 1) + " 次执行）");
                log.info(
                        "[开发域] 任务补丁已快照：task={}，type={}，version={}，文件数={}",
                        task.taskId(),
                        artifact.artifactType(),
                        artifact.version(),
                        session.touchedFiles().size());
                return summary
                        + "；补丁 "
                        + artifact.artifactType()
                        + " v"
                        + artifact.version()
                        + "（"
                        + session.touchedFiles().size()
                        + " 个文件）"
                        + builds;
            }
            return summary + builds;
        } finally {
            agent.close();
        }
    }

    /**
     * 审查任务收尾（P1-7）：结构不完整直接失败；报告先入库再判定阻断，阻断项随任务失败
     * 打回开发，修复后由人工重试同一审查任务直至关闭。
     */
    private String recordReview(DevTask task, Msg reply, String builds) {
        String text = reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
        DevReviewReport report = DevReviewReport.parse(text);
        List<String> problems = new ArrayList<>(report.violations());
        coverageProblem(task, report, artifacts).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            log.warn(
                    "[开发域] 审查报告结构不合法：task={}，问题={}，原文={}",
                    task.taskId(),
                    problems,
                    text.length() > 800 ? text.substring(0, 800) + "…" : text);
            throw new PayrollException("审查报告不完整：" + String.join("；", problems));
        }
        String owner = task.ownerAgent().isBlank() ? DevPathPolicy.ROLE_REVIEWER : task.ownerAgent();
        List<String> reviewed = reviewedTaskIds(task);
        DevArtifact artifact = null;
        for (String reviewedTaskId : reviewed) {
            artifact =
                    artifacts.save(
                            reviewedTaskId,
                            DevArtifactType.REVIEW_REPORT,
                            owner,
                            task.title(),
                            text,
                            null);
        }
        log.info(
                "[开发域] 审查报告已归属：审查任务={}，被审任务={}，版本={}",
                task.taskId(),
                reviewed,
                artifact == null ? 0 : artifact.version());
        if (!report.blocking().isEmpty()) {
            log.warn(
                    "[开发域] 审查发现阻断项，打回开发：task={}，数量={}，证据={}",
                    task.taskId(),
                    report.blocking().size(),
                    report.blockingSummary());
            throw new PayrollException(
                    "审查发现 " + report.blocking().size() + " 个阻断项，已打回："
                            + report.blockingSummary());
        }
        log.info(
                "[开发域] 审查通过：task={}，报告版本={}，结论={}",
                task.taskId(),
                artifact.version(),
                report.summary());
        return report.summary()
                + "；审查报告 "
                + artifact.artifactType()
                + " v"
                + artifact.version()
                + builds;
    }

    /** 护栏命中必须留审计（P0-6/A11）：越权路径、黑名单与只读写都按 fail-closed 处理。 */
    static void auditGuardBreach(
            AuditService audit, String taskId, String role, DevGuardMonitor guard) {
        if (audit == null || guard == null || !guard.breached()) {
            return;
        }
        audit.record(
                "devteam.guard.breach",
                "dev-task",
                taskId,
                null,
                Map.of("role", role, "events", guard.events()));
    }

    /** 构建校验失败会抛出，任务不会产出补丁；未声明校验命令时如实标注而不是声称通过。 */
    private String verifyBuilds(DevTask task, String role, Path workspaceRoot) {
        List<DevBuildResult> results = buildRunner.run(role, workspaceRoot);
        auditBuild(audit, task.taskId(), role, results);
        if (results.isEmpty()) {
            return "；构建校验：未声明";
        }
        String names = String.join("、", results.stream().map(DevBuildResult::name).toList());
        log.info("[开发域] 构建校验通过：role={}，命令={}", role, names);
        return "；构建校验通过：" + names;
    }

    /**
     * A13：编码面 token 用量入追溯。模型未返回 usage（如离线桩）时跳过，不编造数值。
     */
    static void auditUsage(
            AuditService audit, String taskId, String role, Msg reply, DevTeamSettings settings) {
        if (audit == null || reply == null) {
            return;
        }
        ChatUsage usage = reply.getUsage();
        if (usage == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", role);
        payload.put(
                "model",
                settings == null || settings.model().modelId() == null
                        ? ""
                        : settings.model().modelId());
        payload.put("inputTokens", usage.getInputTokens());
        payload.put("outputTokens", usage.getOutputTokens());
        payload.put("cachedTokens", usage.getCachedTokens());
        payload.put("totalTokens", usage.getTotalTokens());
        payload.put("seconds", usage.getTime());
        audit.record("devteam.task.usage", "dev-task", taskId, null, payload);
    }

    /** A13：每条校验命令的退出码与耗时入追溯，供审计还原构建过程。 */
    static void auditBuild(
            AuditService audit, String taskId, String role, List<DevBuildResult> results) {
        if (audit == null || results == null || results.isEmpty()) {
            return;
        }
        List<Map<String, Object>> commands = new ArrayList<>(results.size());
        for (DevBuildResult result : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("command", result.name());
            item.put("image", result.image());
            item.put("exitCode", result.exitCode());
            item.put("timedOut", result.timedOut());
            item.put("durationMillis", result.durationMillis());
            commands.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", role);
        payload.put("commands", commands);
        audit.record("devteam.task.build", "dev-task", taskId, null, payload);
    }

    private static String replySummary(Msg reply) {
        if (reply == null || reply.getTextContent() == null) {
            throw new PayrollException("研发任务执行结束但模型没有返回内容");
        }
        String text = reply.getTextContent().strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new PayrollException("研发任务执行结束但模型返回为空");
        }
        return text.length() <= MAX_SUMMARY_CHARS
                ? text
                : text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }

    /** 任务说明只描述本次目标与验收，不把历史对话混进执行输入。 */
    static String taskPrompt(DevTask task) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 任务：").append(task.title()).append('\n');
        prompt.append("- task_id：").append(task.taskId()).append('\n');
        prompt.append("- role：").append(task.role()).append('\n');
        prompt.append("- 尝试次数：").append(task.attempt()).append('\n');
        if (!task.dependsOn().isEmpty()) {
            prompt.append("- 依赖任务：").append(String.join("、", task.dependsOn())).append('\n');
        }
        prompt.append("\n### 需要产出\n");
        appendItems(prompt, task.outputs());
        prompt.append("\n### 验收标准\n");
        appendItems(prompt, task.acceptance());
        if (!task.note().isBlank()) {
            prompt.append("\n### 上次执行备注\n").append(task.note()).append('\n');
        }
        prompt.append("\n请按角色技能与交接契约执行；完成后用一到三句话总结改动、未完成项与风险。");
        return prompt.toString();
    }

    private static void appendItems(StringBuilder prompt, java.util.List<String> items) {
        if (items.isEmpty()) {
            prompt.append("- （未指定）\n");
            return;
        }
        for (String item : items) {
            prompt.append("- ").append(item).append('\n');
        }
    }

    /**
     * 审查输入（P1-7）：审查角色工作区是仓库基线，看不到尚未交付的补丁，提示词必须带上
     * 依赖任务的四类补丁 diff 与测试报告。缺失时如实标注，不 pretend 已覆盖。
     */
    static String reviewInput(DevTask task, DevArtifactStore artifacts) {
        StringBuilder input = new StringBuilder("\n### 报告格式（必须严格遵守）\n");
        input.append(DevReviewReport.template()).append('\n');
        input.append("\n### 输出约束\n")
                .append("- 逐文件结论最多 12 条，每条只写一次，禁止重复同一行或照抄模板示例\n")
                .append("- 结论必须覆盖下方待审补丁中改动的文件，路径与补丁中的新路径一致\n")
                .append("- 全文不超过 40 行，不要输出计划或过程说明\n");
        input.append("\n### 待审补丁与测试报告\n");
        int used = 0;
        boolean any = false;
        for (String dependency : task.dependsOn()) {
            for (DevArtifactType type : DevArtifactType.values()) {
                if (!type.patch()) {
                    continue;
                }
                Optional<DevArtifact> latest = artifacts.latest(dependency, type);
                if (latest.isEmpty()) {
                    continue;
                }
                DevArtifact artifact = latest.get();
                any = true;
                String content = artifact.content() == null ? "" : artifact.content();
                int budget = Math.max(MAX_REVIEW_INPUT_CHARS - used, 0);
                input.append("\n#### ")
                        .append(dependency)
                        .append(" · ")
                        .append(type.id())
                        .append(" v")
                        .append(artifact.version())
                        .append('\n')
                        .append("```diff\n");
                if (content.length() > budget) {
                    input.append(content, 0, budget)
                            .append("\n... 已截断，完整内容见产物库 ...\n");
                    used = MAX_REVIEW_INPUT_CHARS;
                } else {
                    input.append(content).append('\n');
                    used += content.length();
                }
                input.append("```\n");
            }
            Optional<DevArtifact> tests =
                    artifacts.latest(dependency, DevArtifactType.TEST_REPORT);
            if (tests.isPresent()) {
                any = true;
                String content = tests.get().content() == null ? "" : tests.get().content();
                int budget = Math.max(MAX_REVIEW_INPUT_CHARS - used, 0);
                input.append("\n#### ").append(dependency).append(" · test-report\n");
                if (content.length() > budget) {
                    input.append(content, 0, budget).append("\n... 已截断 ...\n");
                    used = MAX_REVIEW_INPUT_CHARS;
                } else {
                    input.append(content).append('\n');
                    used += content.length();
                }
            }
        }
        if (!any) {
            input.append("- 依赖任务未产出补丁或测试报告，仅能基于当前工作区代码审查。\n");
        }
        return input.toString();
    }

    /**
     * 审查报告归属（P1-7）：G2/G3 与质量报告都按被审任务读取 REVIEW_REPORT，
     * 因此报告写到依赖任务名下；只有无依赖的独立审查任务才写到自己名下，避免重复计数。
     */
    static List<String> reviewedTaskIds(DevTask task) {
        return task.dependsOn().isEmpty() ? List.of(task.taskId()) : task.dependsOn();
    }

    /** 审查结论必须覆盖依赖任务补丁改动的文件；补丁为空时不做该约束。 */
    static Optional<String> coverageProblem(
            DevTask task, DevReviewReport report, DevArtifactStore artifacts) {
        Set<String> patched = new LinkedHashSet<>();
        for (String dependency : task.dependsOn()) {
            for (DevArtifactType type : DevArtifactType.values()) {
                if (!type.patch()) {
                    continue;
                }
                artifacts.latest(dependency, type)
                        .ifPresent(artifact -> patched.addAll(diffPaths(artifact.content())));
            }
        }
        if (patched.isEmpty()) {
            return Optional.empty();
        }
        for (DevReviewReport.Finding finding : report.findings()) {
            if (patched.contains(normalizePath(finding.path()))) {
                return Optional.empty();
            }
        }
        return Optional.of("结论未覆盖依赖任务改动的文件：" + String.join("、", patched));
    }

    static List<String> diffPaths(String diff) {
        List<String> paths = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return paths;
        }
        for (String line : diff.split("\\R")) {
            if (line.startsWith("+++ b/")) {
                String path = line.substring(6).strip();
                if (!path.isEmpty() && !"/dev/null".equals(path)) {
                    paths.add(normalizePath(path));
                }
            }
        }
        return paths;
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("b/") || value.startsWith("a/")) {
            value = value.substring(2);
        }
        return value;
    }

    private static String safe(String value) {
        String normalized = value == null ? "task" : value.trim();
        return normalized.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 工作区根目录：系统属性优先，未配置时落到临时目录下的研发工作区。 */
    public static Path defaultWorkRoot() {
        String configured = System.getProperty("payroll.dev-team.workspace-root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "payroll-dev-workspaces");
    }

    /** 研发仓库根：环境变量优先，其次系统属性，最后工作目录。 */
    public static Path defaultProjectRoot() {
        String configured = System.getenv("PAYROLL_DEV_PROJECT_ROOT");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("payroll.dev-team.project-root");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.dir");
        }
        try {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                return root;
            }
        } catch (RuntimeException e) {
            log.warn("[开发域] 研发仓库根目录配置非法：{}", configured);
        }
        return null;
    }
}
