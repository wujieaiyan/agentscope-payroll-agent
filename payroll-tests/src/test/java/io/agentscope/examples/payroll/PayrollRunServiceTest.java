package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.EmailStatus;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollEmailReport;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import io.agentscope.examples.payroll.service.ApprovalService;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollRunServiceTest {

    private PayrollServices services;
    private PayrollRunService runService;
    private ApprovalService approvalService;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        PayrollDatabase database =
                PayrollDatabase.inMemory("run-" + UUID.randomUUID());
        outputDir = Files.createTempDirectory("payroll-output-");
        services = PayrollServices.forDatabase(database, outputDir);
        runService = services.runService();
        approvalService =
                new ApprovalService(
                        new H2ApprovalStore(database), new H2PayrollRunStore(database));
    }

    /** 走完整审批链路：自动审批通过后返回批次 ID。 */
    private long approvedRun(String period, String scope, Map<String, PayrollAdjustment> adjustments) {
        long runId = runService.preview(period, scope, adjustments).run().runId();
        approvalService.submit(runId, AuthUser.system(), "月度核算");
        approvalService.approve(runId, AuthUser.system(), "同意");
        return runId;
    }

    @Test
    @DisplayName("全员预览生成草稿批次，合计与逐人核算一致")
    void previewsAllEmployees() {
        PayrollRunDetail detail = runService.preview("2026-10", "全部", Map.of());

        assertEquals("DRAFT", detail.run().status());
        assertEquals(6, detail.items().size());
        assertTrue(detail.failures().isEmpty());
        assertEquals(0, detail.run().totalNet().compareTo(new BigDecimal("128747.22")));
        assertEquals(0, detail.run().totalGross().compareTo(new BigDecimal("175280.46")));
    }

    @Test
    @DisplayName("按部门预览只包含该部门员工")
    void previewsDepartment() {
        PayrollRunDetail detail = runService.preview("2026-10", "研发部", Map.of());

        assertEquals(2, detail.items().size());
        assertTrue(detail.items().stream()
                .allMatch(item -> "研发部".equals(item.department())));
    }

    @Test
    @DisplayName("微调绩效奖金与其他补贴后重新核算")
    void appliesAdjustment() {
        PayrollRunDetail detail =
                runService.preview(
                        "2026-10",
                        "全部",
                        Map.of(
                                "EMP001",
                                new PayrollAdjustment(
                                        new BigDecimal("5000.00"),
                                        new BigDecimal("100.00"))));

        PayrollRunItem item = detail.items().stream()
                .filter(row -> "EMP001".equals(row.employeeId()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, item.payroll().performanceBonus().compareTo(new BigDecimal("5000.00")));
        assertEquals(0, item.payroll().otherAllowance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    @DisplayName("出账重新核算并生成 Excel，落库后状态变为已出账")
    void finalizesAndWritesExcel() {
        long runId = approvedRun("2026-10", "全部", Map.of());

        PayrollRunDetail detail = runService.finalizeRun(runId);

        assertEquals("FINALIZED", detail.run().status());
        assertNotNull(detail.run().excelPath());
        Path excel = Path.of(detail.run().excelPath());
        assertTrue(Files.exists(excel));
        assertTrue(excel.toFile().length() > 0);
        assertTrue(detail.items().stream()
                .allMatch(item -> EmailStatus.PENDING.equals(item.emailStatus())));
    }

    @Test
    @DisplayName("发送前必须确认；dry-run 报告区分成功与失败并给出原因")
    void sendsDryRunReport() {
        long runId = approvedRun("2026-10", "全部", Map.of());
        runService.finalizeRun(runId);

        assertThrows(PayrollException.class, () -> runService.sendAll(runId, false));

        PayrollEmailReport report = runService.sendAll(runId, true);

        assertTrue(report.dryRun());
        assertEquals(6, report.total());
        assertEquals(5, report.successCount());
        assertEquals(1, report.failureCount());
        var failed = report.results().stream()
                .filter(result -> EmailStatus.FAILED.equals(result.status()))
                .findFirst()
                .orElseThrow();
        assertEquals("EMP006", failed.employeeId());
        assertTrue(failed.reason().contains("未维护邮箱"));
        assertEquals("SENT", runService.detail(runId).run().status());
        assertThrows(PayrollException.class, () -> runService.sendAll(runId, true));
    }

    @Test
    @DisplayName("未审批通过的批次不允许出账")
    void blocksFinalizeBeforeApproval() {
        long runId = runService.preview("2026-10", "全部", Map.of()).run().runId();

        PayrollException error =
                assertThrows(PayrollException.class, () -> runService.finalizeRun(runId));
        assertTrue(error.getMessage().contains("审批"), error.getMessage());
    }

    @Test
    @DisplayName("存在核算失败员工时禁止提交审批")
    void blocksFinalizeWhenEmployeeFails() {
        services.repository()
                .saveEmployee(
                        new Employee(
                                "EMP100",
                                "缺考勤员工",
                                "测试部",
                                "测试工程师",
                                new BigDecimal("6000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.07"),
                                null,
                                BigDecimal.ZERO,
                                LocalDate.of(2026, 10, 1),
                                "",
                                "missing@example.com",
                                "北京"));
        PayrollRunDetail detail = runService.preview("2026-10", "全部", Map.of());

        assertEquals(6, detail.items().size());
        assertEquals(1, detail.failures().size());
        assertEquals("EMP100", detail.failures().get(0).employeeId());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () ->
                                approvalService.submit(
                                        detail.run().runId(), AuthUser.system(), "提交"));
        assertTrue(error.getMessage().contains("核算失败"), error.getMessage());
    }

    @Test
    @DisplayName("未知批次返回可解释错误")
    void rejectsUnknownRun() {
        PayrollException error =
                assertThrows(PayrollException.class, () -> runService.detail(9999));
        assertTrue(error.getMessage().contains("不存在"));
    }
}
