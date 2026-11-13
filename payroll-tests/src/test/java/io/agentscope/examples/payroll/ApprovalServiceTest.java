package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollApprovalEvent;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.service.ApprovalService;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalServiceTest {

    private static final AuthUser SUBMITTER =
            new AuthUser(11L, "hr01", "人事专员", "HR", "人事", null, null, Set.of());
    private static final AuthUser APPROVER =
            new AuthUser(1L, "admin", "管理员", "ADMIN", "管理员", null, null, Set.of());

    private PayrollDatabase database;
    private PayrollServices services;
    private PayrollRunService runService;
    private H2ApprovalStore approvalStore;
    private ApprovalService approvalService;

    @BeforeEach
    void setUp() throws IOException {
        database = PayrollDatabase.inMemory("approval-" + UUID.randomUUID());
        Path outputDir = Files.createTempDirectory("payroll-approval-");
        services = PayrollServices.forDatabase(database, outputDir);
        runService = services.runService();
        approvalStore = new H2ApprovalStore(database);
        approvalService = new ApprovalService(approvalStore, new H2PayrollRunStore(database));
    }

    @Test
    @DisplayName("提交后进入待审批，审批人通过后写入轨迹与金额指纹")
    void submitsAndApproves() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();

        PayrollRunDetail submitted = approvalService.submit(runId, SUBMITTER, "月度核算");
        assertEquals("PENDING_APPROVAL", submitted.run().status());
        assertEquals("默认审批链", submitted.run().approval().flowName());
        assertEquals("人事专员", submitted.run().approval().submittedByName());
        assertEquals(1, submitted.approvals().size());
        assertEquals(PayrollApprovalEvent.SUBMIT, submitted.approvals().get(0).action());

        PayrollRunDetail approved = approvalService.approve(runId, APPROVER, "同意");
        assertEquals("APPROVED", approved.run().status());
        assertEquals("管理员", approved.run().approval().approvedByName());
        assertEquals(2, approved.approvals().size());
        assertEquals(PayrollApprovalEvent.APPROVE, approved.approvals().get(1).action());
        assertEquals(
                approved.run().approval().snapshotHash(),
                approved.approvals().get(1).snapshotHash());
    }

    @Test
    @DisplayName("提交人不能审批或驳回自己提交的批次")
    void blocksSelfApproval() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();
        approvalService.submit(runId, SUBMITTER, "提交");

        PayrollException approveError =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.approve(runId, SUBMITTER, "自批"));
        assertTrue(approveError.getMessage().contains("不能审批自己"), approveError.getMessage());

        PayrollException rejectError =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.reject(runId, SUBMITTER, "自驳"));
        assertTrue(rejectError.getMessage().contains("不能驳回自己"), rejectError.getMessage());

        assertEquals("PENDING_APPROVAL", approvalService.detail(runId).run().status());
    }

    @Test
    @DisplayName("驳回必须填写原因，驳回后可重新提交并再次审批")
    void rejectsAndResubmits() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();
        approvalService.submit(runId, SUBMITTER, "提交");

        PayrollException blankReason =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.reject(runId, APPROVER, "  "));
        assertTrue(blankReason.getMessage().contains("驳回原因"));

        PayrollRunDetail rejected = approvalService.reject(runId, APPROVER, "奖金需复核");
        assertEquals("REJECTED", rejected.run().status());
        assertEquals("奖金需复核", rejected.run().approval().rejectionReason());
        assertEquals(PayrollApprovalEvent.REJECT, rejected.approvals().get(1).action());

        PayrollRunDetail resubmitted = approvalService.submit(runId, SUBMITTER, "已复核");
        assertEquals("PENDING_APPROVAL", resubmitted.run().status());
        assertEquals(
                "APPROVED",
                approvalService.approve(runId, APPROVER, "通过").run().status());
    }

    @Test
    @DisplayName("审批前批次明细被改动时拒绝审批并提示重新提交")
    void detectsChangedSnapshotOnApprove() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();
        approvalService.submit(runId, SUBMITTER, "提交");
        tamperStoredResult(runId, "EMP001");

        PayrollConflictException error =
                assertThrows(
                        PayrollConflictException.class,
                        () -> approvalService.approve(runId, APPROVER, "同意"));
        assertTrue(error.getMessage().contains("发生变化"), error.getMessage());
        assertEquals("PENDING_APPROVAL", approvalService.detail(runId).run().status());
    }

    @Test
    @DisplayName("审批后考勤变化导致出账重新核算与快照不一致，返回冲突")
    void detectsChangedDataOnFinalize() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();
        approvalService.submit(runId, SUBMITTER, "提交");
        approvalService.approve(runId, APPROVER, "同意");

        AttendanceSummary attendance =
                services.repository()
                        .findAttendance("EMP001", YearMonth.of(2026, 10))
                        .orElseThrow();
        services.repository()
                .saveAttendance(
                        new AttendanceSummary(
                                attendance.employeeId(),
                                attendance.period(),
                                attendance.personalLeaveDays(),
                                attendance.sickLeaveDays(),
                                attendance.absentDays().add(BigDecimal.ONE),
                                attendance.weekdayOvertimeHours(),
                                attendance.restDayOvertimeHours(),
                                attendance.holidayOvertimeHours()));

        PayrollConflictException error =
                assertThrows(PayrollConflictException.class, () -> runService.finalizeRun(runId));
        assertTrue(error.getMessage().contains("审批快照不一致"), error.getMessage());
        assertEquals("APPROVED", runService.detail(runId).run().status());
    }

    @Test
    @DisplayName("部门审批链优先于全局审批链")
    void prefersDepartmentFlow() {
        approvalStore.createFlow(
                new H2ApprovalStore.FlowDraft(
                        "研发部审批链",
                        "DEPARTMENT",
                        "研发部",
                        BigDecimal.ZERO,
                        null,
                        10,
                        true,
                        ""));
        long runId = runService.preview("2026-10", "研发部", Map.of()).run().runId();

        PayrollRunDetail submitted = approvalService.submit(runId, SUBMITTER, "提交");

        assertEquals("研发部审批链", submitted.run().approval().flowName());
        assertEquals("研发部审批链", submitted.approvals().get(0).flowName());
    }

    @Test
    @DisplayName("没有匹配的审批链时给出配置指引")
    void failsWhenNoFlowConfigured() {
        approvalStore.flows().forEach(flow -> approvalStore.deleteFlow(flow.flowId()));
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.submit(runId, SUBMITTER, "提交"));
        assertTrue(error.getMessage().contains("未找到适用的审批链"), error.getMessage());
        assertEquals("DRAFT", runService.detail(runId).run().status());
    }

    @Test
    @DisplayName("核算失败的批次不能提交审批")
    void blocksSubmitWhenFailuresExist() {
        services.repository()
                .saveEmployee(
                        new io.agentscope.examples.payroll.domain.Employee(
                                "EMP100",
                                "缺考勤员工",
                                "测试部",
                                "测试工程师",
                                new BigDecimal("6000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.07"),
                                null,
                                BigDecimal.ZERO,
                                java.time.LocalDate.of(2026, 10, 1),
                                "",
                                "missing@example.com",
                                "北京"));
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> approvalService.submit(runId, SUBMITTER, "提交"));
        assertTrue(error.getMessage().contains("核算失败"), error.getMessage());
    }

    private void tamperStoredResult(long runId, String employeeId) {
        String select =
                "SELECT result_json FROM payroll_details WHERE run_id = ? AND employee_id = ?";
        String update =
                "UPDATE payroll_details SET result_json = ? WHERE run_id = ? AND employee_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement query = connection.prepareStatement(select);
                PreparedStatement statement = connection.prepareStatement(update)) {
            query.setLong(1, runId);
            query.setString(2, employeeId);
            String json;
            try (ResultSet rs = query.executeQuery()) {
                assertTrue(rs.next(), "批次明细不存在：" + employeeId);
                json = rs.getString(1);
            }
            String tampered = json.replaceFirst("\"net_pay\":[0-9.]+", "\"net_pay\":1.00");
            statement.setString(1, tampered);
            statement.setLong(2, runId);
            statement.setString(3, employeeId);
            assertEquals(1, statement.executeUpdate());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
