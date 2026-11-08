package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.domain.ApprovalFlow;
import io.agentscope.examples.payroll.domain.ApprovalStep;
import io.agentscope.examples.payroll.domain.ApprovalTask;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollApproval;
import io.agentscope.examples.payroll.domain.PayrollApprovalEvent;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.domain.PayrollRunFailure;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import io.agentscope.examples.payroll.domain.PayrollRunStatus;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 批次审批状态机：提交 → 通过/驳回；审批通过后才允许出账。
 *
 * <p>提交时把批次明细序列化后取 SHA-256 作为金额指纹，审批与出账都会校验指纹，
 * 任何金额/考勤/政策变化都会要求重新提交审批。提交人不能审批自己提交的批次；
 * 关闭登录认证时使用系统账号，跳过该检查以便本地演示。
 */
public final class ApprovalService {

    private final H2ApprovalStore store;
    private final H2PayrollRunStore runStore;

    public ApprovalService(H2ApprovalStore store, H2PayrollRunStore runStore) {
        this.store = store;
        this.runStore = runStore;
    }

    public PayrollRunDetail submit(long runId, AuthUser actor, String comment) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.DRAFT.equals(run.status())
                && !PayrollRunStatus.REJECTED.equals(run.status())) {
            throw new PayrollException(
                    "只有草稿或已驳回的批次可以提交审批，当前状态：" + run.status());
        }
        List<PayrollRunFailure> failures = runStore.failures(runId);
        if (!failures.isEmpty()) {
            String detail =
                    failures.stream()
                            .map(failure -> failure.employeeId() + "：" + failure.message())
                            .collect(Collectors.joining("；"));
            throw new PayrollException("存在核算失败的员工，不能提交审批：" + detail);
        }
        List<PayrollRunItem> items = runStore.items(runId);
        if (items.isEmpty()) {
            throw new PayrollException("批次没有可审批的明细");
        }
        ApprovalFlow flow =
                store.selectFlow(run.scope(), run.totalNet())
                        .orElseThrow(
                                () ->
                                        new PayrollException(
                                                "未找到适用的审批链，请先在“审批链”页签配置全局或部门流程"));
        List<ApprovalStep> steps = resolveSteps(flow, run.totalNet());
        String actorName = actorName(actor);
        String hash = snapshotHash(run.period(), run.scope(), items);
        if (!store.markSubmitted(
                runId, flow, actorId(actor), actorName, trimComment(comment), hash, steps)) {
            throw new PayrollConflictException("批次状态已变化，请刷新后重新提交");
        }
        store.recordEvent(
                runId, PayrollApprovalEvent.SUBMIT, actorId(actor), actorName, flow,
                trimComment(comment), hash);
        return detail(runId);
    }

    public PayrollRunDetail approve(long runId, AuthUser actor, String comment) {
        PayrollRun run = requireRun(runId);
        requirePending(run);
        requireDifferentActor(run, actor, "不能审批自己提交的批次");
        PayrollApproval approval = run.approval();
        List<ApprovalStep> steps = snapshotSteps(approval);
        int current = currentIndex(approval, steps);
        ApprovalStep step = steps.get(current);
        requireCurrentApprover(step, actor);
        List<PayrollRunItem> items = runStore.items(runId);
        String hash = snapshotHash(run.period(), run.scope(), items);
        if (approval == null
                || approval.snapshotHash() == null
                || !approval.snapshotHash().equals(hash)) {
            throw new PayrollConflictException(
                    "批次数据在提交审批后发生变化，请驳回后重新提交审批");
        }
        String actorName = actorName(actor);
        String trimmed = trimComment(comment);
        boolean last = current >= steps.size() - 1;
        boolean updated =
                last
                        ? store.markApproved(runId, actorId(actor), actorName, trimmed, hash, current)
                        : store.advanceStep(runId, current, current + 1, hash);
        if (!updated) {
            throw new PayrollConflictException("批次状态已变化，请刷新后重试");
        }
        store.recordEvent(
                runId,
                last ? PayrollApprovalEvent.APPROVE : PayrollApprovalEvent.STEP_APPROVE,
                actorId(actor),
                actorName,
                flowOf(run, hash),
                trimmed,
                hash,
                step.order(),
                step.name());
        return detail(runId);
    }

    /** 当前登录用户可处理的审批任务；系统管理员在关闭认证时可见全部待办。 */
    public List<ApprovalTask> tasks(AuthUser actor) {
        if (actor == null) {
            return List.of();
        }
        List<ApprovalTask> pending = store.pendingTasks();
        if (actor.id() == 0L) {
            return pending;
        }
        return pending.stream()
                .filter(task -> task.submittedById() == null || task.submittedById() != actor.id())
                .filter(task -> task.step().matches(actor))
                .toList();
    }

    public PayrollRunDetail reject(long runId, AuthUser actor, String reason) {
        PayrollRun run = requireRun(runId);
        requirePending(run);
        requireDifferentActor(run, actor, "不能驳回自己提交的批次");
        List<ApprovalStep> steps = snapshotSteps(run.approval());
        requireCurrentApprover(steps.get(currentIndex(run.approval(), steps)), actor);
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new PayrollException("请填写驳回原因");
        }
        if (trimmed.length() > 500) {
            throw new PayrollException("驳回原因不能超过 500 个字符");
        }
        String actorName = actorName(actor);
        if (!store.markRejected(runId, actorId(actor), actorName, trimmed)) {
            throw new PayrollConflictException("批次状态已变化，请刷新后重试");
        }
        store.recordEvent(
                runId,
                PayrollApprovalEvent.REJECT,
                actorId(actor),
                actorName,
                flowOf(run, null),
                trimmed,
                run.approval() == null ? null : run.approval().snapshotHash());
        return detail(runId);
    }

    public List<PayrollApprovalEvent> events(long runId) {
        requireRun(runId);
        return store.events(runId);
    }

    public PayrollRunDetail detail(long runId) {
        PayrollRun run = requireRun(runId);
        return new PayrollRunDetail(
                run, runStore.items(runId), runStore.failures(runId), store.events(runId));
    }

    /** 提交时的金额指纹：期间、范围与逐人结果（含微调）的稳定序列化。 */
    public static String snapshotHash(
            String period, String scope, List<PayrollRunItem> items) {
        StringBuilder builder = new StringBuilder();
        builder.append(period).append('|').append(scope).append('\n');
        items.stream()
                .sorted(Comparator.comparing(PayrollRunItem::employeeId))
                .forEach(
                        item ->
                                builder.append(item.employeeId())
                                        .append('|')
                                        .append(JsonSupport.toJson(item.adjustment()))
                                        .append('|')
                                        .append(JsonSupport.toJson(item.payroll()))
                                        .append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按实发合计过滤审批节点；未配置节点时退化为单级“任意审批权限”。 */
    private static List<ApprovalStep> resolveSteps(ApprovalFlow flow, BigDecimal amount) {
        if (flow.steps().isEmpty()) {
            return List.of(ApprovalStep.permissionFallback());
        }
        List<ApprovalStep> applicable =
                flow.steps().stream().filter(step -> step.covers(amount)).toList();
        if (applicable.isEmpty()) {
            throw new PayrollException(
                    "实发合计 " + amount + " 未匹配任何审批节点，请检查审批链“"
                            + flow.name() + "”的节点金额区间");
        }
        return applicable;
    }

    private static List<ApprovalStep> snapshotSteps(PayrollApproval approval) {
        if (approval == null || approval.workflowSteps().isEmpty()) {
            return List.of(ApprovalStep.permissionFallback());
        }
        return approval.workflowSteps();
    }

    private static int currentIndex(PayrollApproval approval, List<ApprovalStep> steps) {
        int current = approval == null ? 0 : approval.currentStep();
        return Math.max(0, Math.min(current, steps.size() - 1));
    }

    /**
     * 关闭认证时系统账号（id=0）代表本地演示，直接放行节点校验；
     * 未配置节点时的权限回退由接口层 {@code payroll:approve} 兜底。
     */
    private static void requireCurrentApprover(ApprovalStep step, AuthUser actor) {
        if (actor == null
                || actor.id() == 0L
                || ApprovalStep.PERMISSION.equals(step.approverType())
                || step.matches(actor)) {
            return;
        }
        throw new PayrollException(
                "当前审批节点「" + step.name() + "」由" + step.approverLabel() + "处理");
    }

    private ApprovalFlow flowOf(PayrollRun run, String hash) {
        PayrollApproval approval = run.approval();
        if (approval == null || approval.flowId() == null) {
            return null;
        }
        return store.findFlow(approval.flowId())
                .orElseGet(
                        () ->
                                new ApprovalFlow(
                                        approval.flowId(),
                                        approval.flowName(),
                                        ApprovalFlow.GLOBAL,
                                        "*",
                                        null,
                                        null,
                                        0,
                                        false,
                                        "",
                                        List.of(),
                                        null,
                                        null));
    }

    private static void requirePending(PayrollRun run) {
        if (!PayrollRunStatus.PENDING_APPROVAL.equals(run.status())) {
            throw new PayrollException("批次不在待审批状态，当前状态：" + run.status());
        }
    }

    private static void requireDifferentActor(PayrollRun run, AuthUser actor, String message) {
        if (actor == null || actor.id() == 0L) {
            return;
        }
        PayrollApproval approval = run.approval();
        if (approval != null && Objects.equals(approval.submittedById(), actor.id())) {
            throw new PayrollException(message);
        }
    }

    private PayrollRun requireRun(long runId) {
        return runStore
                .findRun(runId)
                .orElseThrow(() -> new PayrollException("工资批次不存在：" + runId));
    }

    private static Long actorId(AuthUser actor) {
        return actor == null ? null : actor.id();
    }

    private static String actorName(AuthUser actor) {
        if (actor == null) {
            return "system";
        }
        return actor.displayName() == null || actor.displayName().isBlank()
                ? actor.username()
                : actor.displayName();
    }

    private static String trimComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > 500) {
            throw new PayrollException("审批备注不能超过 500 个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
