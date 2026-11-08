package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ApprovalTask;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollApprovalEvent;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.service.ApprovalService;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowTest {

    private static final AuthUser SUBMITTER =
            new AuthUser(11L, "hr01", "人事专员", "HR", "人事", null, null, Set.of());
    private static final AuthUser ADMIN =
            new AuthUser(1L, "admin", "管理员", "ADMIN", "管理员", null, null, Set.of());
    private static final AuthUser VIEWER =
            new AuthUser(2L, "viewer1", "只读访客", "VIEWER", "只读访客", null, null,
                    Set.of("payroll:approve"));

    private PayrollServices services;
    private PayrollRunService runService;
    private H2ApprovalStore approvalStore;
    private ApprovalService approvalService;

    @BeforeEach
    void setUp() throws IOException {
        PayrollDatabase database = PayrollDatabase.inMemory("workflow-" + UUID.randomUUID());
        Path outputDir = Files.createTempDirectory("payroll-workflow-");
        services = PayrollServices.forDatabase(database, outputDir);
        runService = services.runService();
        approvalStore = new H2ApprovalStore(database);
        approvalService = new ApprovalService(approvalStore, new H2PayrollRunStore(database));
    }

    @Test
    @DisplayName("两级审批按节点推进，待办只出现在当前节点审批人名下")
    void advancesThroughStepsAndFiltersTasks() {
        approvalStore.createFlow(
                flowWithSteps(
                        "研发部两级审批",
                        step("财务复核", "ROLE", "ADMIN", null, null),
                        step("负责人确认", "ROLE", "VIEWER", null, null)));
        long runId = runService.preview("2026-10", "研发部", Map.of()).run().runId();

        PayrollRunDetail submitted = approvalService.submit(runId, SUBMITTER, "提交");
        assertEquals("PENDING_APPROVAL", submitted.run().status());
        assertEquals(2, submitted.run().approval().workflowSteps().size());
        assertEquals(0, submitted.run().approval().currentStep());
        assertEquals(
                List.of("财务复核", "负责人确认"),
                submitted.run().approval().workflowSteps().stream()
                        .map(step -> step.name())
                        .toList());

        assertTrue(containsRun(approvalService.tasks(ADMIN), runId));
        assertFalse(containsRun(approvalService.tasks(VIEWER), runId));
        assertFalse(containsRun(approvalService.tasks(SUBMITTER), runId));

        PayrollException wrongApprover =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.approve(runId, VIEWER, "越级审批"));
        assertTrue(wrongApprover.getMessage().contains("当前审批节点"), wrongApprover.getMessage());

        PayrollRunDetail first =
                approvalService.approve(runId, ADMIN, "财务确认");
        assertEquals("PENDING_APPROVAL", first.run().status());
        assertEquals(1, first.run().approval().currentStep());
        assertEquals(
                PayrollApprovalEvent.STEP_APPROVE, first.approvals().get(1).action());
        assertEquals("财务复核", first.approvals().get(1).stepName());

        assertFalse(containsRun(approvalService.tasks(ADMIN), runId));
        assertTrue(containsRun(approvalService.tasks(VIEWER), runId));

        PayrollException repeated =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.approve(runId, ADMIN, "重复审批"));
        assertTrue(repeated.getMessage().contains("当前审批节点"), repeated.getMessage());

        PayrollRunDetail approved =
                approvalService.approve(runId, VIEWER, "负责人确认");
        assertEquals("APPROVED", approved.run().status());
        assertEquals(PayrollApprovalEvent.APPROVE, approved.approvals().get(2).action());
        assertEquals("负责人确认", approved.approvals().get(2).stepName());
        assertTrue(approvalService.tasks(VIEWER).isEmpty());
    }

    @Test
    @DisplayName("提交后修改审批链不影响已提交批次的节点快照")
    void snapshotsStepsAtSubmit() {
        H2ApprovalStore store = approvalStore;
        var flow =
                store.createFlow(
                        flowWithSteps(
                                "快照审批链",
                                step("一级", "ROLE", "ADMIN", null, null),
                                step("二级", "ROLE", "VIEWER", null, null)));
        long runId = runService.preview("2026-10", "研发部", Map.of()).run().runId();
        approvalService.submit(runId, SUBMITTER, "提交");

        store.updateFlow(
                flow.flowId(),
                flowWithSteps("快照审批链", step("唯一节点", "ROLE", "ADMIN", null, null)));

        PayrollRunDetail first = approvalService.approve(runId, ADMIN, "一级通过");
        assertEquals("PENDING_APPROVAL", first.run().status());
        assertEquals(2, first.run().approval().workflowSteps().size());

        PayrollRunDetail approved = approvalService.approve(runId, VIEWER, "二级通过");
        assertEquals("APPROVED", approved.run().status());
    }

    @Test
    @DisplayName("节点金额区间按实发合计过滤，全部不匹配时拒绝提交")
    void filtersStepsByAmount() {
        long runId = runService.preview("2026-10", "研发部", Map.of()).run().runId();
        BigDecimal amount = runService.detail(runId).run().totalNet();
        approvalStore.createFlow(
                flowWithSteps(
                        "金额分段审批",
                        step("小额审批", "ROLE", "ADMIN", null, amount.subtract(BigDecimal.ONE)),
                        step("大额审批", "ROLE", "ADMIN", amount, null)));

        PayrollRunDetail submitted = approvalService.submit(runId, SUBMITTER, "提交");
        assertEquals(1, submitted.run().approval().workflowSteps().size());
        assertEquals("大额审批", submitted.run().approval().workflowSteps().get(0).name());

        approvalStore.updateFlow(
                approvalStore.flows().stream()
                        .filter(item -> "金额分段审批".equals(item.name()))
                        .findFirst()
                        .orElseThrow()
                        .flowId(),
                flowWithSteps(
                        "金额分段审批",
                        step("超出上限", "ROLE", "ADMIN", null, amount.subtract(BigDecimal.ONE))));
        long secondRun = runService.preview("2026-10", "研发部", Map.of()).run().runId();
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.submit(secondRun, SUBMITTER, "提交"));
        assertTrue(error.getMessage().contains("未匹配任何审批节点"), error.getMessage());
    }

    @Test
    @DisplayName("节点配置校验角色、用户与金额区间")
    void validatesStepConfiguration() {
        PayrollException unknownRole =
                assertThrows(
                        PayrollException.class,
                        () ->
                                approvalStore.createFlow(
                                        flowWithSteps(
                                                "未知角色",
                                                step("复核", "ROLE", "CFO", null, null))));
        assertTrue(unknownRole.getMessage().contains("审批角色不存在"), unknownRole.getMessage());

        PayrollException unknownUser =
                assertThrows(
                        PayrollException.class,
                        () ->
                                approvalStore.createFlow(
                                        flowWithSteps(
                                                "未知用户",
                                                step("复核", "USER", "nobody", null, null))));
        assertTrue(unknownUser.getMessage().contains("审批用户不存在"), unknownUser.getMessage());

        PayrollException badAmount =
                assertThrows(
                        PayrollException.class,
                        () ->
                                approvalStore.createFlow(
                                        flowWithSteps(
                                                "金额倒挂",
                                                step(
                                                        "复核",
                                                        "ROLE",
                                                        "HR",
                                                        new BigDecimal("100"),
                                                        new BigDecimal("50")))));
        assertTrue(badAmount.getMessage().contains("节点金额上限"), badAmount.getMessage());
    }

    private boolean containsRun(List<ApprovalTask> tasks, long runId) {
        return tasks.stream().anyMatch(task -> task.runId() == runId);
    }

    private static H2ApprovalStore.FlowDraft flowWithSteps(
            String name, H2ApprovalStore.StepDraft... steps) {
        return new H2ApprovalStore.FlowDraft(
                name,
                "DEPARTMENT",
                "研发部",
                BigDecimal.ZERO,
                null,
                10,
                true,
                "",
                List.of(steps));
    }

    private static H2ApprovalStore.StepDraft step(
            String name,
            String type,
            String value,
            BigDecimal minAmount,
            BigDecimal maxAmount) {
        return new H2ApprovalStore.StepDraft(name, type, value, minAmount, maxAmount);
    }
}
