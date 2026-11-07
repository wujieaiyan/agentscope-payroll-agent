package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.domain.EmailStatus;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollApproval;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollEmailReport;
import io.agentscope.examples.payroll.domain.PayrollEmailResult;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.domain.PayrollRunFailure;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import io.agentscope.examples.payroll.domain.PayrollRunStatus;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工资批次编排：预览（可微调）→ 出账落库 + Excel → 确认后全员 dry-run 发送。
 *
 * <p>出账时会重新调用确定性引擎，避免“预览后被修改的考核/政策”带入错误结果。
 */
public final class PayrollRunService {

    private final PayrollEngine engine;
    private final H2PayrollRepository repository;
    private final H2PayrollRunStore runStore;
    private final ExcelExporter excelExporter;
    private final EmailGateway emailGateway;
    private final Path outputDir;

    public PayrollRunService(
            PayrollEngine engine,
            H2PayrollRepository repository,
            H2PayrollRunStore runStore,
            ExcelExporter excelExporter,
            EmailGateway emailGateway,
            Path outputDir) {
        this.engine = engine;
        this.repository = repository;
        this.runStore = runStore;
        this.excelExporter = excelExporter;
        this.emailGateway = emailGateway;
        this.outputDir = outputDir;
    }

    public PayrollRunDetail preview(
            String periodText, String scopeText, Map<String, PayrollAdjustment> adjustments) {
        YearMonth period = parsePeriod(periodText);
        Map<String, PayrollAdjustment> requestedAdjustments =
                adjustments == null ? Map.of() : adjustments;
        String requestedScope = scopeText == null ? "" : scopeText.trim();
        boolean all =
                requestedScope.isEmpty()
                        || "全部".equals(requestedScope)
                        || "全员".equals(requestedScope);
        List<Employee> targets =
                repository.employees().stream()
                        .filter(e -> all || e.department().equals(requestedScope))
                        .toList();
        if (targets.isEmpty()) {
            throw new PayrollException(
                    "未找到部门「"
                            + requestedScope
                            + "」的在职员工，可选部门："
                            + repository.employees().stream()
                                    .map(Employee::department)
                                    .distinct()
                                    .collect(Collectors.joining("、")));
        }
        Set<String> targetIds =
                targets.stream().map(Employee::employeeId).collect(Collectors.toSet());
        for (String employeeId : requestedAdjustments.keySet()) {
            if (!targetIds.contains(employeeId.trim().toUpperCase())) {
                throw new PayrollException(
                        "微调指定的员工不在本批次范围内：" + employeeId);
            }
        }

        List<PayrollRunItem> items = new ArrayList<>();
        List<PayrollRunFailure> failures = new ArrayList<>();
        for (Employee employee : targets) {
            PayrollAdjustment adjustment =
                    requestedAdjustments.get(employee.employeeId().toUpperCase());
            try {
                PayrollResult result = engine.calculate(employee.employeeId(), period, adjustment);
                items.add(
                        new PayrollRunItem(
                                employee.employeeId(),
                                employee.name(),
                                employee.department(),
                                employee.city(),
                                employee.email(),
                                adjustment,
                                result,
                                EmailStatus.PENDING,
                                null));
            } catch (RuntimeException e) {
                failures.add(
                        new PayrollRunFailure(
                                employee.employeeId(),
                                e.getMessage() == null ? "未知核算错误" : e.getMessage()));
            }
        }
        if (items.isEmpty()) {
            String detail =
                    failures.stream()
                            .map(f -> f.employeeId() + "：" + f.message())
                            .collect(Collectors.joining("；"));
            throw new PayrollException("本批次全部员工核算失败：" + detail);
        }
        PayrollRun run =
                runStore.createDraft(
                        period.toString(), all ? "全部" : requestedScope, items, failures);
        return new PayrollRunDetail(run, items, failures, List.of());
    }

    public PayrollRunDetail finalizeRun(long runId) {
        PayrollRun run = requireRun(runId);
        if (PayrollRunStatus.isFinal(run.status())) {
            return detail(runId);
        }
        if (!PayrollRunStatus.APPROVED.equals(run.status())) {
            throw new PayrollException(finalizeStatusMessage(run.status()));
        }
        List<PayrollRunFailure> failures = runStore.failures(runId);
        if (!failures.isEmpty()) {
            String detail =
                    failures.stream()
                            .map(f -> f.employeeId() + "：" + f.message())
                            .collect(Collectors.joining("；"));
            throw new PayrollException("存在核算失败的员工，不能出账：" + detail);
        }
        List<PayrollRunItem> items = runStore.items(runId);
        if (items.isEmpty()) {
            throw new PayrollException("批次没有可出账的明细");
        }
        YearMonth period = parsePeriod(run.period());
        List<PayrollRunItem> recalculatedItems = new ArrayList<>(items.size());
        for (PayrollRunItem item : items) {
            PayrollResult recalculated =
                    engine.calculate(item.employeeId(), period, item.adjustment());
            recalculatedItems.add(
                    new PayrollRunItem(
                            item.employeeId(),
                            item.employeeName(),
                            item.department(),
                            item.city(),
                            item.email(),
                            item.adjustment(),
                            recalculated,
                            item.emailStatus(),
                            item.emailReason()));
        }
        PayrollApproval approval = run.approval();
        String currentHash =
                ApprovalService.snapshotHash(
                        run.period(), run.scope(), recalculatedItems);
        if (approval == null
                || approval.snapshotHash() == null
                || !approval.snapshotHash().equals(currentHash)) {
            throw new PayrollConflictException(
                    "核算结果与审批快照不一致（审批后政策/考勤/考核发生变化），请重新提交审批");
        }
        for (PayrollRunItem item : recalculatedItems) {
            runStore.updateResult(runId, item.payroll());
        }
        PayrollRunDetail detail = detail(runId);
        Path excel =
                excelExporter.write(
                        detail.run(), detail.items(), detail.failures(), outputDir);
        if (!runStore.markFinalized(runId, excel.toString())) {
            throw new PayrollConflictException("批次状态已变化，请刷新后重试");
        }
        return detail(runId);
    }

    public PayrollEmailReport sendAll(long runId, boolean confirm) {
        if (!confirm) {
            throw new PayrollException("发送全员邮件前必须确认工资数据无误（confirm=true）");
        }
        PayrollRun run = requireRun(runId);
        if ("SENT".equals(run.status())) {
            throw new PayrollException("该批次已执行过发送，禁止重复发送");
        }
        if (!"FINALIZED".equals(run.status())) {
            throw new PayrollException("批次尚未出账，不能发送工资条邮件");
        }
        List<PayrollRunItem> items = runStore.items(runId);
        if (items.isEmpty()) {
            throw new PayrollException("批次没有可发送的工资明细");
        }
        List<PayrollEmailResult> results = emailGateway.send(run.period(), items);
        runStore.recordEmailResults(runId, results);
        runStore.markStatus(runId, "SENT");

        PayrollRunDetail detail = detail(runId);
        Path excel =
                excelExporter.write(
                        detail.run(), detail.items(), detail.failures(), outputDir);
        runStore.updateExcelPath(runId, excel.toString());

        int success =
                (int)
                        results.stream()
                                .filter(r -> EmailStatus.isSuccess(r.status()))
                                .count();
        return new PayrollEmailReport(
                runId,
                run.period(),
                true,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                results.size(),
                success,
                results.size() - success,
                results);
    }

    public PayrollRunDetail detail(long runId) {
        PayrollRun run = requireRun(runId);
        return new PayrollRunDetail(
                run, runStore.items(runId), runStore.failures(runId), List.of());
    }

    public List<PayrollRun> runs() {
        return runStore.runs();
    }

    public Path outputDir() {
        return outputDir;
    }

    private PayrollRun requireRun(long runId) {
        return runStore
                .findRun(runId)
                .orElseThrow(() -> new PayrollException("工资批次不存在：" + runId));
    }

    private static String finalizeStatusMessage(String status) {
        return switch (status) {
            case PayrollRunStatus.DRAFT -> "批次尚未提交审批，不能出账";
            case PayrollRunStatus.PENDING_APPROVAL -> "批次正在等待审批，审批通过后才能出账";
            case PayrollRunStatus.REJECTED -> "批次已被驳回，请修改后重新提交审批";
            default -> "批次状态不允许出账：" + status;
        };
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new PayrollException("请提供工资期间，格式 YYYY-MM，例如 2026-10");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new PayrollException("工资期间格式错误：" + period + "，应为 YYYY-MM");
        }
    }

    /** 供管理台把列表形式的微调转换为按工号索引。 */
    public static Map<String, PayrollAdjustment> indexAdjustments(
            List<AdjustmentInput> inputs) {
        Map<String, PayrollAdjustment> index = new LinkedHashMap<>();
        if (inputs == null) {
            return index;
        }
        for (AdjustmentInput input : inputs) {
            if (input.employeeId() == null || input.employeeId().isBlank()) {
                continue;
            }
            PayrollAdjustment adjustment =
                    new PayrollAdjustment(
                            input.performanceBonus(), input.otherAllowance());
            if (!adjustment.isEmpty()) {
                index.put(input.employeeId().trim().toUpperCase(), adjustment);
            }
        }
        return index;
    }

    /** 管理台请求中的单条微调；金额为 null 表示沿用系统结果。 */
    public record AdjustmentInput(
            String employeeId, java.math.BigDecimal performanceBonus,
            java.math.BigDecimal otherAllowance) {
    }
}
