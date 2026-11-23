package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevDeliveryStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P2-3 质量评分：扣分口径、回归集与团队效果报表。 */
class DevQualityReportServiceTest {

    private static final String PLAN = "plan-p2-3";
    private static final String BRANCH = "dev-team/quality";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-18T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final List<DevArtifactType> PATCH_TYPES =
            List.of(
                    DevArtifactType.PATCH_BACKEND,
                    DevArtifactType.PATCH_FRONTEND,
                    DevArtifactType.PATCH_DB,
                    DevArtifactType.PATCH_DEVOPS);

    private static final String CLEAN_REVIEW =
            """
            ### 检查清单
            - [ ] 契约一致性
            - [ ] 正确性
            - [ ] 安全边界
            - [ ] 测试覆盖
            - [通过] src/main/java/Foo.java:10 — 实现符合接口
            - [建议] src/main/java/Bar.java:20 — 可补充边界测试
            """;

    private DevTaskStore tasks;
    private DevArtifactStore artifacts;
    private DevApprovalStore approvals;
    private DevDeliveryStore deliveries;
    private DevQualityReportService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("dev-quality-" + System.nanoTime());
        tasks = new DevTaskStore(database);
        artifacts = new DevArtifactStore(database);
        approvals = new DevApprovalStore(database);
        deliveries = new DevDeliveryStore(database);
        service = new DevQualityReportService(tasks, artifacts, approvals, deliveries, CLOCK);
    }

    @Test
    @DisplayName("干净链路：全部完成、审查通过、G2/G3 放行时评 100 分")
    void cleanApprovedPlanScoresFull() {
        seedPlanTasks(
                PLAN,
                DevPathPolicy.ROLE_IMPLEMENTER,
                DevPathPolicy.ROLE_FRONTEND);
        seedDoneTask(PLAN, id(PLAN, "t1"), DevPathPolicy.ROLE_IMPLEMENTER, DevArtifactType.PATCH_BACKEND);
        seedDoneTask(PLAN, id(PLAN, "t2"), DevPathPolicy.ROLE_FRONTEND, DevArtifactType.PATCH_FRONTEND);
        approveAll(PLAN);
        DevDelivery delivery = openDelivery(PLAN);
        deliveries.decide(delivery.deliveryId(), DevDeliveryStatus.APPROVED, COMMIT, "alice", "已建分支");

        DevQualityReport report = service.report(PLAN);

        assertEquals(2, report.tasks());
        assertEquals(2, report.done());
        assertEquals(0, report.failed());
        assertEquals(0, report.retries());
        assertEquals(2, report.patchCount());
        assertEquals(Map.of("patch-backend", 1, "patch-frontend", 1), report.patchesByType());
        assertEquals(2, report.reviewReports());
        assertEquals(0, report.reviewBlocking());
        assertEquals(2, report.reviewSuggestions());
        assertEquals("approved", report.approvalStatus());
        assertEquals("approved", report.deliveryStatus());
        assertEquals(COMMIT, report.deliveryCommit());
        assertTrue(report.deductions().isEmpty(), report.deductions().toString());
        assertEquals(100, report.score());
        assertEquals("达标", report.grade());
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0), report.generatedAt());

        String markdown = report.markdown();
        assertTrue(markdown.contains("# 研发质量报告：" + PLAN), markdown);
        assertTrue(markdown.contains("评分：100/100（达标）"), markdown);
        assertTrue(markdown.contains("01234567"), markdown);
        assertTrue(markdown.contains("- 无"), markdown);
    }

    @Test
    @DisplayName("失败、重试、阻断、缺审查与未审批分别扣分并写明原因")
    void penalizesFailuresRetriesAndBlocking() {
        seedPlanTasks(PLAN, DevPathPolicy.ROLE_IMPLEMENTER, DevPathPolicy.ROLE_FRONTEND);
        tasks.retry(PLAN, id(PLAN, "t1"), "上一轮失败后重试");
        tasks.finish(PLAN, id(PLAN, "t1"), DevTaskStatus.DONE, "完成", "");
        tasks.retry(PLAN, id(PLAN, "t2"), "上一轮失败后重试");
        tasks.finish(PLAN, id(PLAN, "t2"), DevTaskStatus.FAILED, "", "编译失败");
        artifacts.save(
                id(PLAN, "t1"), DevArtifactType.PATCH_BACKEND, "impl", "后端补丁", "diff t1", "hash-t1");
        artifacts.save(
                id(PLAN, "t1"),
                DevArtifactType.REVIEW_REPORT,
                "alice",
                "审查报告",
                CLEAN_REVIEW.replace(
                        "- [建议] src/main/java/Bar.java:20 — 可补充边界测试",
                        "- [阻断] src/main/java/Baz.java:88 — 空指针风险"),
                null);
        artifacts.save(
                id(PLAN, "t2"), DevArtifactType.PATCH_FRONTEND, "fe", "前端补丁", "diff t2", "hash-t2");

        DevQualityReport report = service.report(PLAN);

        assertEquals(1, report.done());
        assertEquals(1, report.failed());
        assertEquals(2, report.retries());
        assertEquals(1, report.reviewBlocking());
        assertEquals(0, report.reviewSuggestions());
        assertEquals("none", report.approvalStatus());
        assertEquals("none", report.deliveryStatus());
        assertEquals(46, report.score());
        assertEquals("不达标", report.grade());

        List<String> deductions = report.deductions();
        assertEquals(5, deductions.size(), deductions.toString());
        assertTrue(deductions.contains("失败/阻塞任务 1 个（-10）"), deductions.toString());
        assertTrue(deductions.contains("人工重试 2 次（-4）"), deductions.toString());
        assertTrue(deductions.contains("审查阻断 1 项（-10）"), deductions.toString());
        assertTrue(deductions.contains("补丁缺少审查报告 1 份（-10）"), deductions.toString());
        assertTrue(deductions.contains("补丁未通过 G2 批量审批（-20）"), deductions.toString());
        assertTrue(report.markdown().contains("- 补丁未通过 G2 批量审批（-20）"), report.markdown());
    }

    @Test
    @DisplayName("交付被驳回或补丁漂移扣 10 分")
    void rejectedOrStaleDeliveryDeducts() {
        seedApprovedPlan(PLAN);
        DevDelivery rejected = openDelivery(PLAN);
        deliveries.decide(rejected.deliveryId(), DevDeliveryStatus.REJECTED, "", "alice", "需返工");

        DevQualityReport rejectedReport = service.report(PLAN);
        assertEquals("rejected", rejectedReport.deliveryStatus());
        assertEquals(90, rejectedReport.score());
        assertTrue(
                rejectedReport.deductions().contains("交付被驳回或补丁漂移（-10）"),
                rejectedReport.deductions().toString());

        String stalePlan = "plan-p2-3-stale";
        seedApprovedPlan(stalePlan);
        DevDelivery stale = openDelivery(stalePlan);
        deliveries.decide(stale.deliveryId(), DevDeliveryStatus.STALE, "", "alice", "补丁已漂移");

        DevQualityReport staleReport = service.report(stalePlan);
        assertEquals("stale", staleReport.deliveryStatus());
        assertEquals(90, staleReport.score());
        assertTrue(staleReport.deductions().contains("交付被驳回或补丁漂移（-10）"));
    }

    @Test
    @DisplayName("回归集只保留已通过的交付，附分支、commit 与当时评分")
    void regressionCasesFromApprovedDeliveries() {
        seedApprovedPlan(PLAN);
        DevDelivery approved = openDelivery(PLAN);
        deliveries.decide(
                approved.deliveryId(), DevDeliveryStatus.APPROVED, COMMIT, "alice", "已建分支");

        String rejectedPlan = "plan-p2-3-rejected";
        seedApprovedPlan(rejectedPlan);
        DevDelivery rejected = openDelivery(rejectedPlan);
        deliveries.decide(rejected.deliveryId(), DevDeliveryStatus.REJECTED, "", "alice", "需返工");

        List<DevRegressionCase> cases =
                service.regressionCases(List.of(rejectedPlan, PLAN, PLAN));

        assertEquals(1, cases.size(), cases.toString());
        DevRegressionCase regression = cases.get(0);
        assertEquals(PLAN, regression.planId());
        assertEquals(approved.deliveryId(), regression.deliveryId());
        assertEquals(BRANCH, regression.branch());
        assertEquals(COMMIT, regression.commitId());
        assertEquals(1, regression.patchCount());
        assertEquals(100, regression.score());
        assertEquals("达标", regression.grade());
        assertTrue(regression.deliveredAt() != null, "交付时间应来自决策时间");
        assertTrue(service.regressionCases(List.of()).isEmpty());
    }

    @Test
    @DisplayName("团队报表汇总计划评分、角色执行与回归基线")
    void teamReportRendersSections() {
        seedApprovedPlan(PLAN);
        DevDelivery approved = openDelivery(PLAN);
        deliveries.decide(
                approved.deliveryId(), DevDeliveryStatus.APPROVED, COMMIT, "alice", "已建分支");

        String failing = "plan-p2-3-failing";
        seedPlanTasks(failing, DevPathPolicy.ROLE_QA, DevPathPolicy.ROLE_IMPLEMENTER);
        tasks.finish(failing, id(failing, "t1"), DevTaskStatus.FAILED, "", "测试失败");
        tasks.finish(failing, id(failing, "t2"), DevTaskStatus.FAILED, "", "构建失败");

        String report = service.teamReport(List.of(PLAN, failing));

        assertTrue(report.contains("# 研发团队效果报表"), report);
        assertTrue(report.contains("- 计划数：2"), report);
        assertTrue(report.contains("- 平均评分：90"), report);
        assertTrue(report.contains("- 达标计划：1/2"), report);
        assertTrue(
                report.contains("| plan-p2-3 | 100 | 达标 | 1/2 | 0 | approved | approved |"),
                report);
        assertTrue(
                report.contains("| plan-p2-3-failing | 80 | 需关注 | 0/2 | 0 | none | none |"),
                report);
        assertTrue(report.contains("| project-implementer | 2 | 1 | 1 | 0 |"), report);
        assertTrue(report.contains("| project-qa | 1 | 0 | 1 | 0 |"), report);
        assertTrue(report.contains("## 回归基线"), report);
        assertTrue(report.contains("| plan-p2-3 | dev-team/quality | 01234567 | 1 | 100 |"), report);
        assertFalse(report.contains("| plan-p2-3-failing | dev-team/quality"), report);
    }

    @Test
    @DisplayName("无任务的计划按空链路计满分，非法计划 id 直接拒绝")
    void emptyPlanAndInvalidIds() {
        DevQualityReport empty = service.report("plan-empty");
        assertEquals(0, empty.tasks());
        assertEquals(100, empty.score());
        assertEquals("none", empty.approvalStatus());

        assertThrows(PayrollException.class, () -> service.report("  "));
        assertThrows(PayrollException.class, () -> service.report("x".repeat(65)));
        assertThrows(PayrollException.class, () -> service.teamReport(List.of(" ")));

        String noPlans = service.teamReport(List.of());
        assertTrue(noPlans.contains("- 计划数：0"), noPlans);
        assertFalse(noPlans.contains("平均评分"), noPlans);
        assertTrue(noPlans.contains("- 暂无已通过的交付"), noPlans);
    }

    private static String id(String plan, String suffix) {
        return plan + "-" + suffix;
    }

    /** 建两个待执行任务：t1 用第一个角色，t2 用第二个角色。 */
    private void seedPlanTasks(String plan, String firstRole, String secondRole) {
        tasks.replacePlan(
                plan,
                List.of(
                        DevTask.pending(
                                plan, id(plan, "t1"), "任务 t1", firstRole, "alice", 1,
                                List.of(), List.of(), List.of()),
                        DevTask.pending(
                                plan, id(plan, "t2"), "任务 t2", secondRole, "alice", 2,
                                List.of(), List.of(), List.of())));
    }

    /** 造一条干净链路：任务完成 + 补丁 + 审查报告 + G2 通过。 */
    private void seedApprovedPlan(String plan) {
        seedPlanTasks(plan, DevPathPolicy.ROLE_IMPLEMENTER, DevPathPolicy.ROLE_FRONTEND);
        seedDoneTask(plan, id(plan, "t1"), DevPathPolicy.ROLE_IMPLEMENTER, DevArtifactType.PATCH_BACKEND);
        approveAll(plan);
    }

    private void seedDoneTask(String plan, String taskId, String role, DevArtifactType patchType) {
        tasks.finish(plan, taskId, DevTaskStatus.DONE, "完成", "");
        artifacts.save(
                taskId,
                patchType,
                "alice",
                "补丁",
                "diff --git a/app b/app\n+1\n",
                "hash-" + taskId);
        artifacts.save(taskId, DevArtifactType.REVIEW_REPORT, "alice", "审查报告", CLEAN_REVIEW, null);
    }

    private void approveAll(String plan) {
        List<DevApprovalItem> items = new ArrayList<>();
        for (DevTask task : tasks.list(plan)) {
            for (DevArtifactType type : PATCH_TYPES) {
                artifacts.latest(task.taskId(), type)
                        .ifPresent(artifact -> items.add(DevApprovalItem.pending(artifact)));
            }
        }
        DevApprovalBatch batch = approvals.create(plan, "补丁审批", items);
        List<DevApprovalItem> decided =
                batch.items().stream()
                        .map(item -> item.decide(DevApprovalStatus.APPROVED, ""))
                        .toList();
        assertTrue(
                approvals.decide(batch.batchId(), DevApprovalStatus.APPROVED, decided, "alice", "同意"));
    }

    /** 按现有补丁打开一份交付申请（不推送）。 */
    private DevDelivery openDelivery(String plan) {
        List<DevDeliveryPatch> patches = new ArrayList<>();
        for (DevTask task : tasks.list(plan)) {
            for (DevArtifactType type : PATCH_TYPES) {
                artifacts.latest(task.taskId(), type)
                        .ifPresent(
                                artifact ->
                                        patches.add(
                                                new DevDeliveryPatch(
                                                        artifact.taskId(),
                                                        artifact.artifactType(),
                                                        artifact.version(),
                                                        artifact.diffHash())));
            }
        }
        return deliveries.create(plan, BRANCH, COMMIT, "交付：" + plan, "未推送", patches);
    }
}
