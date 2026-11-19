package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.devteam.DevArtifactType;
import io.agentscope.examples.payroll.devteam.DevBuildResult;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevReviewReport;
import io.agentscope.examples.payroll.devteam.DevTask;
import io.agentscope.examples.payroll.devteam.DevTaskStatus;
import io.agentscope.examples.payroll.domain.AuditPage;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevLlmTaskRunnerTest {

    @Test
    @DisplayName("审查输入带出依赖任务的补丁 diff 与测试报告")
    void reviewInputCarriesPatchAndTestReport() {
        DevArtifactStore artifacts =
                new DevArtifactStore(PayrollDatabase.inMemory("dev-review-input-" + System.nanoTime()));
        artifacts.save(
                "n1",
                DevArtifactType.PATCH_BACKEND,
                "dev-agent",
                "实现个性化推荐",
                "+++ b/backend/personalize.js\n+module.exports = {};",
                null);
        artifacts.save(
                "n1",
                DevArtifactType.TEST_REPORT,
                "dev-agent",
                "自测",
                "node --test：3 个用例通过",
                null);
        DevTask review =
                new DevTask(
                        0L,
                        "plan-1",
                        "n2",
                        "代码审查：个性化推荐",
                        "project-reviewer",
                        "dev-reviewer",
                        1,
                        DevTaskStatus.READY,
                        List.of("n1"),
                        List.of("review-report.md"),
                        List.of("检查清单四项齐全"),
                        "",
                        0,
                        "",
                        null,
                        null,
                        null,
                        null);

        String input = DevLlmTaskRunner.reviewInput(review, artifacts);

        assertTrue(input.contains("### 报告格式（必须严格遵守）"), input);
        assertTrue(input.contains("### 检查清单"), input);
        assertTrue(input.contains("### 待审补丁与测试报告"), input);
        assertTrue(input.contains("n1 · patch-backend v1"), input);
        assertTrue(input.contains("+module.exports = {};"), input);
        assertTrue(input.contains("n1 · test-report"), input);
        assertTrue(input.contains("3 个用例通过"), input);
    }

    @Test
    @DisplayName("依赖任务没有补丁时如实标注，不虚构审查输入")
    void reviewInputMarksMissingPatches() {
        DevArtifactStore artifacts =
                new DevArtifactStore(PayrollDatabase.inMemory("dev-review-empty-" + System.nanoTime()));
        DevTask review =
                new DevTask(
                        0L,
                        "plan-1",
                        "n2",
                        "代码审查",
                        "project-reviewer",
                        "dev-reviewer",
                        1,
                        DevTaskStatus.READY,
                        List.of("n1"),
                        List.of("review-report.md"),
                        List.of(),
                        "",
                        0,
                        "",
                        null,
                        null,
                        null,
                        null);

        String input = DevLlmTaskRunner.reviewInput(review, artifacts);

        assertTrue(input.contains("依赖任务未产出补丁或测试报告"), input);
        assertFalse(input.contains("```diff"), input);
    }

    @Test
    @DisplayName("护栏命中写入审计，未命中不产生噪声")
    void guardBreachIsAudited() {
        PayrollDatabase database = PayrollDatabase.inMemory("dev-guard-audit-" + System.nanoTime());
        AuditService audit = new AuditService(new H2AuditStore(database), 30);
        DevGuardMonitor guard = new DevGuardMonitor();

        DevLlmTaskRunner.auditGuardBreach(audit, "n1", "project-implementer", guard);
        assertTrue(
                audit.query(AuditQuery.firstPage(10)).items().isEmpty(),
                "未命中不应写审计");

        guard.breach("read", "../etc/passwd", "路径超出项目根");
        DevLlmTaskRunner.auditGuardBreach(audit, "n1", "project-implementer", guard);

        AuditPage page = audit.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        assertEquals("devteam.guard.breach", page.items().get(0).action());
        assertTrue(page.items().get(0).afterJson().contains("../etc/passwd"));
    }

    @Test
    @DisplayName("任务 token 用量入审计，模型未返回 usage 时不编造")
    void usageIsAudited() {
        PayrollDatabase database = PayrollDatabase.inMemory("dev-usage-audit-" + System.nanoTime());
        AuditService audit = new AuditService(new H2AuditStore(database), 30);

        Msg withoutUsage = Msg.builderForRole(MsgRole.ASSISTANT).textContent("ok").build();
        DevLlmTaskRunner.auditUsage(audit, "n1", "project-implementer", withoutUsage, null);
        assertTrue(
                audit.query(AuditQuery.firstPage(10)).items().isEmpty(), "无 usage 不应写审计");

        Msg reply =
                Msg.builderForRole(MsgRole.ASSISTANT)
                        .textContent("ok")
                        .usage(new ChatUsage(120, 80, 1.5))
                        .build();
        DevLlmTaskRunner.auditUsage(audit, "n1", "project-implementer", reply, null);

        AuditPage page = audit.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        assertEquals("devteam.task.usage", page.items().get(0).action());
        String after = page.items().get(0).afterJson();
        assertTrue(after.contains("\"inputTokens\":120"), after);
        assertTrue(after.contains("\"totalTokens\":200"), after);
        assertTrue(after.contains("\"role\":\"project-implementer\""), after);
    }

    @Test
    @DisplayName("构建校验命令与退出码入审计")
    void buildResultsAreAudited() {
        PayrollDatabase database =
                PayrollDatabase.inMemory("dev-build-audit-" + System.nanoTime());
        AuditService audit = new AuditService(new H2AuditStore(database), 30);

        DevLlmTaskRunner.auditBuild(
                audit,
                "n1",
                "project-implementer",
                List.of(
                        new DevBuildResult("mvn test", "maven:3.9", 0, false, 1200, ""),
                        new DevBuildResult("npm run build", "node:20", 2, false, 800, "err")));

        AuditPage page = audit.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        assertEquals("devteam.task.build", page.items().get(0).action());
        String after = page.items().get(0).afterJson();
        assertTrue(after.contains("\"command\":\"mvn test\""), after);
        assertTrue(after.contains("\"exitCode\":2"), after);
        assertTrue(after.contains("\"timedOut\":false"), after);
    }

    @Test
    @DisplayName("审查报告归属被审任务，独立审查任务写到自己名下")
    void reviewedTaskIdsCoverDependencies() {
        DevTask review = reviewTask(List.of("n1", "n3"));
        assertEquals(List.of("n1", "n3"), DevLlmTaskRunner.reviewedTaskIds(review));

        DevTask adhoc = reviewTask(List.of());
        assertEquals(List.of("n2"), DevLlmTaskRunner.reviewedTaskIds(adhoc));
    }

    @Test
    @DisplayName("审查结论必须覆盖依赖任务补丁改动的文件")
    void coverageProblemRequiresPatchedFiles() {
        DevArtifactStore artifacts =
                new DevArtifactStore(PayrollDatabase.inMemory("dev-review-cover-" + System.nanoTime()));
        artifacts.save(
                "n1",
                DevArtifactType.PATCH_BACKEND,
                "dev-agent",
                "实现个性化推荐",
                """
                diff --git a/backend/personalize.js b/backend/personalize.js
                --- /dev/null
                +++ b/backend/personalize.js
                +module.exports = {};
                """,
                null);
        DevTask review = reviewTask(List.of("n1"));

        assertTrue(
                DevLlmTaskRunner.coverageProblem(review, DevReviewReport.parse(reviewReport("backend/server.js")), artifacts)
                        .orElseThrow()
                        .contains("backend/personalize.js"));
        assertTrue(
                DevLlmTaskRunner.coverageProblem(
                                review,
                                DevReviewReport.parse(reviewReport("./backend/personalize.js")),
                                artifacts)
                        .isEmpty());
    }

    private static DevTask reviewTask(List<String> dependsOn) {
        return new DevTask(
                0L,
                "plan-1",
                "n2",
                "代码审查",
                "project-reviewer",
                "dev-reviewer",
                1,
                DevTaskStatus.READY,
                dependsOn,
                List.of("review-report.md"),
                List.of(),
                "",
                0,
                "",
                null,
                null,
                null,
                null);
    }

    private static String reviewReport(String path) {
        return """
                ## 审查报告
                ### 检查清单
                - [ ] 契约一致性
                - [ ] 正确性
                - [ ] 安全边界
                - [ ] 测试覆盖
                ### 逐文件结论
                - [通过] %s:12 — 实现符合契约
                """
                .formatted(path);
    }

    @Test
    @DisplayName("任务提示词只包含本次目标、产物、验收、依赖与上次备注")
    void taskPromptCarriesTaskContract() {
        DevTask task =
                new DevTask(
                        0L,
                        "plan-1",
                        "n1",
                        "实现工资单导出",
                        "project-implementer",
                        "dev-agent",
                        2,
                        DevTaskStatus.READY,
                        List.of("n0"),
                        List.of("Excel 导出接口", "单测通过"),
                        List.of("mvn test 通过"),
                        "上次失败：缺少依赖",
                        1,
                        "compile error",
                        null,
                        null,
                        null,
                        null);

        String prompt = DevLlmTaskRunner.taskPrompt(task);

        assertTrue(prompt.contains("## 任务：实现工资单导出"), prompt);
        assertTrue(prompt.contains("- task_id：n1"), prompt);
        assertTrue(prompt.contains("- role：project-implementer"), prompt);
        assertTrue(prompt.contains("- 尝试次数：1"), prompt);
        assertTrue(prompt.contains("- 依赖任务：n0"), prompt);
        assertTrue(prompt.contains("- Excel 导出接口"), prompt);
        assertTrue(prompt.contains("- mvn test 通过"), prompt);
        assertTrue(prompt.contains("### 上次执行备注\n上次失败：缺少依赖"), prompt);
        assertTrue(prompt.contains("总结改动、未完成项与风险"), prompt);
    }

    @Test
    @DisplayName("可写角色映射到补丁类型，只读角色不产出补丁")
    void patchTypeFollowsRole() {
        assertEquals(
                DevArtifactType.PATCH_BACKEND,
                DevArtifactType.patchForRole("project-implementer").orElseThrow());
        assertEquals(
                DevArtifactType.PATCH_FRONTEND,
                DevArtifactType.patchForRole("project-frontend").orElseThrow());
        assertEquals(
                DevArtifactType.PATCH_DB,
                DevArtifactType.patchForRole("project-dba").orElseThrow());
        assertEquals(
                DevArtifactType.PATCH_DEVOPS,
                DevArtifactType.patchForRole("project-devops").orElseThrow());
        assertTrue(DevArtifactType.patchForRole("project-reviewer").isEmpty());
        assertTrue(DevArtifactType.patchForRole("project-product").isEmpty());
    }
}
