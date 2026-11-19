package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.DevLlmTaskRunner;
import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevDeliveryStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.team.TeamIntent;
import io.agentscope.examples.payroll.team.TeamPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端到端验收（非默认回归）：在真实仓库上用编码模型跑「计划 → 编码 → 审查 → G2 → G3 → 质量报告」。
 *
 * <p>默认跳过；设置 {@code PAYROLL_DEV_E2E_PROJECT} 指向目标 Git 仓库、并配置
 * {@code PAYROLL_DEV_MODEL=ollama:<模型>} 后才会执行，避免把有限模型与耗时带进全量回归。
 * 任务运行在隔离克隆里，交付只在目标仓库本地建分支与提交，不推送。
 */
class DevTeamAiGuideE2eTest {

    private static final String ENV_PROJECT = "PAYROLL_DEV_E2E_PROJECT";
    private static final String ENV_BRANCH = "PAYROLL_DEV_E2E_BRANCH";
    private static final String PROJECT_ROOT_PROPERTY = "payroll.dev-team.project-root";

    @AfterEach
    void restore() {
        System.clearProperty(PROJECT_ROOT_PROPERTY);
        DevPathPolicy.resetProfile();
    }

    @Test
    @DisplayName("AI 导购智能体：编码任务产出补丁，审查通过后经 G2/G3 交付并生成质量报告")
    void deliversPersonalizedRecommendationFeature() throws Exception {
        String project = System.getenv(ENV_PROJECT);
        Assumptions.assumeTrue(
                project != null && !project.isBlank(),
                "未设置 " + ENV_PROJECT + "，跳过端到端验收");
        Path projectRoot = Path.of(project).toAbsolutePath().normalize();
        Assumptions.assumeTrue(
                Files.isDirectory(projectRoot.resolve(".git")),
                "目标目录不是 Git 仓库：" + projectRoot);

        String token = Long.toString(System.currentTimeMillis(), 36);
        String planId = "plan-e2e-ai-guide-" + token;
        String branch =
                System.getenv(ENV_BRANCH) == null || System.getenv(ENV_BRANCH).isBlank()
                        ? "dev-team/ai-guide-" + token
                        : System.getenv(ENV_BRANCH).trim();
        System.setProperty(PROJECT_ROOT_PROPERTY, projectRoot.toString());

        Path workRoot = Path.of("target", "e2e-ai-guide-workspaces").toAbsolutePath();
        Path profile = Path.of("target", "e2e-ai-guide-profile.json").toAbsolutePath();
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, profileJson(), StandardCharsets.UTF_8);
        DevPathPolicy.loadProfile(profile);

        PayrollDatabase database = PayrollDatabase.inMemory("dev-e2e-ai-guide-" + token);
        DevTaskStore taskStore = new DevTaskStore(database);
        DevArtifactStore artifactStore = new DevArtifactStore(database);
        DevApprovalStore approvalStore = new DevApprovalStore(database);
        DevDeliveryStore deliveryStore = new DevDeliveryStore(database);
        AuditService audit = new AuditService(new H2AuditStore(database), 30);

        DevTeamSettings settings =
                DevTeamSettings.of(
                        "workspace-write",
                        "batch",
                        // E2E 将单任务超时放宽到 600s、输出上限放宽到 8192（生产默认 300s/4096），其余参数与 D1 一致。
                        new DevModelSettings(
                                "ollama:qwen2.5:3b-instruct", null, 32_768, 8_192, 0.2D, 600));
        assertTrue(settings.writeEnabled(), settings.disabledReason());

        DevBuildRunner buildRunner =
                (role, workspace) -> {
                    assertTrue(
                            DevPathPolicy.verifyCommands(role).isEmpty(),
                            "E2E profile 不应声明校验命令：" + role);
                    return List.of();
                };
        DevLlmTaskRunner runner =
                new DevLlmTaskRunner(
                        settings, artifactStore, projectRoot, workRoot, audit, buildRunner);
        DevTaskBoard board = new DevTaskBoard(taskStore);
        DevApprovalService approvalService =
                new DevApprovalService(taskStore, artifactStore, approvalStore, audit);
        DevDeliveryService deliveryService =
                new DevDeliveryService(
                        taskStore, artifactStore, approvalService, deliveryStore, projectRoot, workRoot, audit);
        DevQualityReportService reportService =
                new DevQualityReportService(taskStore, artifactStore, approvalStore, deliveryStore);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        DevTaskExecutor executor = new DevTaskExecutor(board, runner, pool, audit);
        try {
            board.publish(plan(planId));
            executor.submit(planId, "n1");
            assertTrue(executor.awaitIdle(Duration.ofMinutes(15)), "编码任务执行超时");
            DevTask implementer = board.task(planId, "n1");
            assertEquals(DevTaskStatus.DONE, implementer.status(), implementer.note());

            DevArtifact patch =
                    artifactStore
                            .latest("n1", DevArtifactType.PATCH_BACKEND)
                            .orElseThrow(() -> new AssertionError("编码任务没有产出后端补丁"));
            assertTrue(patch.content().contains("+++ b/backend/personalize.js"), patch.content());
            List<String> touched =
                    patch.content().lines()
                            .filter(line -> line.startsWith("+++ b/"))
                            .map(line -> line.substring("+++ b/".length()).strip())
                            .toList();
            assertFalse(touched.isEmpty(), patch.content());
            for (String path : touched) {
                assertTrue(path.startsWith("backend/"), "补丁越出 backend/ 白名单：" + path);
            }

            executor.submit(planId, "n2");
            assertTrue(executor.awaitIdle(Duration.ofMinutes(15)), "审查任务执行超时");
            DevTask review = board.task(planId, "n2");
            assertEquals(DevTaskStatus.DONE, review.status(), review.note());

            DevApprovalBatch batch = approvalService.open(planId);
            DevApprovalBatch approved = approvalService.approveAll(batch.batchId(), "alice");
            assertEquals(DevApprovalStatus.APPROVED, approved.status());

            DevDelivery delivery = deliveryService.open(planId, branch);
            DevDelivery delivered = deliveryService.approve(delivery.deliveryId(), "alice");
            assertEquals(DevDeliveryStatus.APPROVED, delivered.status(), delivered.note());
            assertNotNull(delivered.commitId(), "交付应写入 commit id");
            assertEquals(40, delivered.commitId().length());

            DevQualityReport report = reportService.report(planId);
            assertEquals(2, report.tasks());
            assertEquals(2, report.done());
            assertEquals(1, report.patchCount());
            assertEquals(1, report.reviewReports());
            assertEquals(0, report.reviewBlocking());
            assertEquals(100, report.score(), report.deductions().toString());
            assertEquals("达标", report.grade());

            String result =
                    report.markdown()
                            + "\n## 端到端交付信息\n\n"
                            + "- 仓库：" + projectRoot + "\n"
                            + "- 计划：" + planId + "\n"
                            + "- 分支：" + delivered.branch() + "\n"
                            + "- commit：" + delivered.commitId() + "\n"
                            + "- 编码任务摘要：" + implementer.note() + "\n"
                            + "- 审查任务摘要：" + review.note() + "\n";
            Path resultFile = Path.of("target", "e2e-ai-guide-result.md").toAbsolutePath();
            Files.writeString(resultFile, result, StandardCharsets.UTF_8);
            System.out.println(result);
        } finally {
            pool.shutdownNow();
        }
    }

    private static TeamPlan plan(String planId) {
        TeamPlan.PlanNode implementer =
                new TeamPlan.PlanNode(
                        "n1",
                        FlowNode.TYPE_AGENT,
                        DevPathPolicy.ROLE_IMPLEMENTER,
                        "ai-guide-implementer",
                        "实现个性化导购推荐",
                        "",
                        "",
                        List.of(
                                "backend/personalize.js",
                                "backend/personalize.test.js"),
                        List.of(
                                "当前工作区已启用 write_file / read_file / edit_file 文件工具，必须用 write_file 实际创建文件",
                                "只在回复里描述代码或给出补丁片段视为未完成，本次验收检查工作区中是否存在这两个文件",
                                "先读取 backend/recommend.js、backend/catalog.js、backend/recommend.test.js，沿用 CommonJS 写法和代码风格",
                                "新增 backend/personalize.js：导出 personalize(products, preferences = {}, limit = 5)",
                                "preferences.maxPrice 为可选数字：有值时过滤掉 price 高于预算的商品",
                                "preferences.tags 为可选字符串数组：按 tags 命中数降序排序，命中数相同按 price 升序",
                                "返回最多 limit 个商品副本并附加 score 字段（命中数），不修改传入的 products 数组",
                                "preferences 为空时按原顺序返回前 limit 个商品",
                                "新增 backend/personalize.test.js：使用 node:test 与 node:assert/strict，覆盖预算过滤、标签优先排序、limit 截断三个用例",
                                "只新增上述两个文件，不改动 server.js、package.json 与其他已有文件；完成后用一到三句话总结"));
        TeamPlan.PlanNode reviewer =
                new TeamPlan.PlanNode(
                        "n2",
                        FlowNode.TYPE_AGENT,
                        DevPathPolicy.ROLE_REVIEWER,
                        "ai-guide-reviewer",
                        "代码审查：个性化导购推荐",
                        "",
                        "",
                        List.of("review-report.md"),
                        List.of(
                                "最终回复只输出报告本身，以 ## 审查报告 开头；不要输出计划、过程说明或“接下来”“我将”等未完成语句",
                                "严格照抄提示词里的报告格式：先输出 ### 检查清单 四行，再输出 ### 逐文件结论",
                                "每个结论行必须是 路径:数字行号 — 具体说明；不得输出「说明」「行号」「建议与阻断」等占位词，不得照抄模板示例",
                                "检查清单必须原样出现这四行：- [ ] 契约一致性、- [ ] 正确性、- [ ] 安全边界、- [ ] 测试覆盖",
                                "逐文件结论立即给出，至少 3 条且必须包含 backend/personalize.js 与 backend/personalize.test.js；格式：- [通过] 文件:行号 — 说明",
                                "结论依据提示词中的待审补丁 diff；行号按 diff 中新增内容估算",
                                "每条结论只写一次，禁止重复同一行；逐文件结论不超过 12 条",
                                "仓库基线可运行，不要编造阻断项；发现的问题写成 [建议]",
                                "整份报告不超过 30 行；写完最后一条结论后立即结束，不要追加其它内容"));
        return new TeamPlan(
                planId,
                TeamIntent.TASK_OTHER,
                "dev-custom",
                List.of(implementer, reviewer),
                List.of(new TeamPlan.PlanEdge("e1", "n1", "n2", null)),
                Map.of(),
                "端到端验收：新增个性化导购推荐能力",
                List.of());
    }

    private static String profileJson() {
        return """
                {
                  "roles": {
                    "project-implementer": {"read": ["**"], "write": ["backend/**"]},
                    "project-reviewer": {"read": ["**"]},
                    "project-qa": {"read": ["**"]}
                  }
                }
                """;
    }
}
