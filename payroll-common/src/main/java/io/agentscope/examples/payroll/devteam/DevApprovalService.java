package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevApprovalStore;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G2 任务级批量审批（P0-8）：一次确认覆盖一个计划的四类补丁，逐任务通过或打回。
 *
 * <p>条目在打开批次时锚定产物的 diff hash；决策时重新读取产物库最新版本，
 * hash 漂移的条目置为 {@link DevApprovalStatus#STALE}，绝不在用户未见过的内容上放行。
 * 条件更新保证批次只决策一次，重复确认直接返回当前状态。
 */
public final class DevApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DevApprovalService.class);
    private static final List<DevArtifactType> PATCH_TYPES =
            List.of(
                    DevArtifactType.PATCH_BACKEND,
                    DevArtifactType.PATCH_FRONTEND,
                    DevArtifactType.PATCH_DB,
                    DevArtifactType.PATCH_DEVOPS);
    private static final Set<String> SENSITIVE_BUILD_FILES =
            Set.of(
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "package.json",
                    "package-lock.json",
                    "pnpm-lock.yaml",
                    "yarn.lock",
                    "requirements.txt",
                    "pyproject.toml",
                    "poetry.lock",
                    "go.mod",
                    "go.sum",
                    "cargo.toml",
                    "cargo.lock");
    private static final String APPROVE_NOTE = "G2 批量确认通过";
    private static final String REJECT_NOTE = "G2 打回";
    private static final String STALE_NOTE = "补丁在确认期间已更新，请重新审阅最新版本";

    private final DevTaskStore tasks;
    private final DevArtifactStore artifacts;
    private final DevApprovalStore batches;
    private final AuditService audit;

    public DevApprovalService(
            DevTaskStore tasks,
            DevArtifactStore artifacts,
            DevApprovalStore batches,
            AuditService audit) {
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.batches = batches;
        this.audit = audit;
    }

    /** 汇总计划内每个任务的最新四类补丁，生成一个待确认批次。 */
    public DevApprovalBatch open(String planId) {
        List<DevTask> planTasks = tasks.list(planId);
        List<DevApprovalItem> items = new ArrayList<>();
        for (DevTask task : planTasks) {
            for (DevArtifactType type : PATCH_TYPES) {
                artifacts
                        .latest(task.taskId(), type)
                        .map(DevApprovalItem::pending)
                        .ifPresent(items::add);
            }
        }
        if (items.isEmpty()) {
            throw new PayrollException("计划暂无可审批的补丁：" + planId);
        }
        String summary = summarize(planTasks, items) + reviewSummary(planTasks);
        Set<String> perTask = new LinkedHashSet<>();
        for (DevApprovalItem item : items) {
            if (requiresPerTask(item)) {
                perTask.add(item.taskId());
            }
        }
        if (!perTask.isEmpty()) {
            summary += "；需逐任务确认：" + String.join("、", perTask);
        }
        DevApprovalBatch batch = batches.create(planId, summary, items);
        audit("devteam.g2.open", batch, null, snapshot(batch));
        log.info(
                "[G2] 打开审批批次：batchId={}，planId={}，任务={}，补丁={}",
                batch.batchId(),
                batch.planId(),
                planTasks.size(),
                items.size());
        return batch;
    }

    public Optional<DevApprovalBatch> find(String batchId) {
        return batches.find(batchId);
    }

    public List<DevApprovalBatch> list(String planId) {
        return batches.list(planId);
    }

    public List<DevApprovalBatch> pending(String planId) {
        return batches.listPending(planId);
    }

    /** 全部通过：仍逐条核对 diff hash，漂移的条目转为 STALE。 */
    public DevApprovalBatch approveAll(String batchId, String actor) {
        DevApprovalBatch batch = require(batchId);
        Set<String> taskIds = new LinkedHashSet<>();
        Set<String> perTask = new LinkedHashSet<>();
        for (DevApprovalItem item : batch.items()) {
            taskIds.add(item.taskId());
            if (requiresPerTask(item)) {
                perTask.add(item.taskId());
            }
        }
        if (!perTask.isEmpty()) {
            log.warn("[G2] 敏感补丁拒绝批量放行，要求逐任务确认：batchId={}，任务={}", batchId, perTask);
            throw new PayrollException(
                    "以下任务改动契约/迁移/依赖，必须逐任务确认（decide）："
                            + String.join("、", perTask));
        }
        return decide(batchId, taskIds, actor, APPROVE_NOTE);
    }

    /**
     * D12：契约变更、迁移脚本与依赖升级不走批量确认。
     *
     * <p>判定依据是补丁 diff 中 {@code +++ b/} 的路径：{@code interfaces.md}、
     * 迁移目录下的 SQL、以及构建/依赖清单文件。
     */
    static boolean requiresPerTask(String diff) {
        if (diff == null || diff.isBlank()) {
            return false;
        }
        for (String raw : diff.split("\\R")) {
            if (!raw.startsWith("+++ b/")) {
                continue;
            }
            String path = raw.substring(6).strip().toLowerCase(Locale.ROOT);
            int slash = path.lastIndexOf('/');
            String file = slash >= 0 ? path.substring(slash + 1) : path;
            if ("interfaces.md".equals(file)) {
                return true;
            }
            if (file.endsWith(".sql") && path.contains("migration")) {
                return true;
            }
            if (SENSITIVE_BUILD_FILES.contains(file)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresPerTask(DevApprovalItem item) {
        return latestArtifact(item).map(artifact -> requiresPerTask(artifact.content())).orElse(false);
    }

    /**
     * 逐任务决策：{@code approvedTaskIds} 内的任务放行，其余打回。
     *
     * <p>批次状态：存在 STALE 条目为 STALE；否则至少放行一条为 APPROVED，全部打回为 REJECTED。
     * 已决策的批次直接返回当前状态，重复调用无副作用。
     */
    public DevApprovalBatch decide(
            String batchId,
            Collection<String> approvedTaskIds,
            String actor,
            String note) {
        DevApprovalBatch batch = require(batchId);
        if (!batch.pending()) {
            return batch;
        }
        Set<String> approved = normalize(approvedTaskIds, batch);
        String reason = note == null ? "" : note.trim();
        List<DevApprovalItem> decided = new ArrayList<>();
        boolean stale = false;
        boolean anyApproved = false;
        for (DevApprovalItem item : batch.items()) {
            if (!approved.contains(item.taskId())) {
                decided.add(item.decide(DevApprovalStatus.REJECTED, orDefault(reason, REJECT_NOTE)));
                continue;
            }
            Optional<DevArtifact> latest = latestArtifact(item);
            if (latest.isEmpty() || !item.diffHash().equals(latest.get().diffHash())) {
                decided.add(item.decide(DevApprovalStatus.STALE, STALE_NOTE));
                stale = true;
                continue;
            }
            decided.add(item.decide(DevApprovalStatus.APPROVED, orDefault(reason, APPROVE_NOTE)));
            anyApproved = true;
        }
        DevApprovalStatus status =
                stale ? DevApprovalStatus.STALE
                        : anyApproved ? DevApprovalStatus.APPROVED : DevApprovalStatus.REJECTED;
        if (!batches.decide(batchId, status, decided, actor, reason)) {
            log.info("[G2] 决策竞争失败，返回当前状态：batchId={}", batchId);
            return require(batchId);
        }
        DevApprovalBatch result = require(batchId);
        audit("devteam.g2.decide", result, snapshot(batch), snapshot(result));
        log.info(
                "[G2] 审批完成：batchId={}，状态={}，放行={}，打回={}，过期={}，操作人={}",
                batchId,
                status.wireName(),
                count(result, DevApprovalStatus.APPROVED),
                count(result, DevApprovalStatus.REJECTED),
                count(result, DevApprovalStatus.STALE),
                actor);
        return result;
    }

    private Set<String> normalize(Collection<String> approvedTaskIds, DevApprovalBatch batch) {
        Set<String> known = new LinkedHashSet<>();
        for (DevApprovalItem item : batch.items()) {
            known.add(item.taskId());
        }
        Set<String> approved = new LinkedHashSet<>();
        if (approvedTaskIds != null) {
            for (String taskId : approvedTaskIds) {
                String value = taskId == null ? "" : taskId.trim();
                if (!value.isEmpty()) {
                    approved.add(value);
                }
            }
        }
        for (String taskId : approved) {
            if (!known.contains(taskId)) {
                throw new PayrollException("审批批次中不存在任务：" + taskId);
            }
        }
        return approved;
    }

    private Optional<DevArtifact> latestArtifact(DevApprovalItem item) {
        return DevArtifactType.parse(item.artifactType())
                .flatMap(type -> artifacts.latest(item.taskId(), type));
    }

    private DevApprovalBatch require(String batchId) {
        return batches
                .find(batchId)
                .orElseThrow(() -> new PayrollNotFoundException("审批批次不存在"));
    }

    /**
     * P1-7：存在未关闭的审查阻断项（或报告不完整）时拒绝进入 G2；
     * 报告存在但无阻断时把结论并入批次摘要。
     */
    private String reviewSummary(List<DevTask> planTasks) {
        List<String> blocking = new ArrayList<>();
        int reports = 0;
        int suggests = 0;
        for (DevTask task : planTasks) {
            Optional<DevArtifact> latest =
                    artifacts.latest(task.taskId(), DevArtifactType.REVIEW_REPORT);
            if (latest.isEmpty()) {
                continue;
            }
            DevReviewReport report = DevReviewReport.parse(latest.get().content());
            if (!report.violations().isEmpty()) {
                blocking.add(
                        task.taskId() + " 报告不完整（" + String.join("；", report.violations()) + "）");
                continue;
            }
            reports++;
            suggests += report.count(DevReviewReport.Verdict.SUGGEST);
            if (!report.blocking().isEmpty()) {
                blocking.add(
                        task.taskId()
                                + " "
                                + report.blocking().size()
                                + " 个阻断（"
                                + report.blockingSummary()
                                + "）");
            }
        }
        if (!blocking.isEmpty()) {
            throw new PayrollException(
                    "存在未关闭的审查阻断项，不能进入 G2：" + String.join("；", blocking));
        }
        return "；审查："
                + reports
                + " 份报告，0 阻断"
                + (suggests > 0 ? "，建议 " + suggests + " 项" : "");
    }

    private static String summarize(List<DevTask> tasks, List<DevApprovalItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DevApprovalItem item : items) {
            counts.merge(item.artifactType(), 1, Integer::sum);
        }
        StringBuilder summary = new StringBuilder();
        summary.append("G2 批量确认：")
                .append(tasks.size())
                .append(" 个任务 / ")
                .append(items.size())
                .append(" 份补丁");
        String[] labels = {"后端", "前端", "数据库", "运维"};
        int index = 0;
        boolean first = true;
        for (DevArtifactType type : PATCH_TYPES) {
            int count = counts.getOrDefault(type.id(), 0);
            if (count == 0) {
                index++;
                continue;
            }
            summary.append(first ? "（" : "，");
            summary.append(labels[index]).append(' ').append(count);
            first = false;
            index++;
        }
        if (!first) {
            summary.append('）');
        }
        return summary.toString();
    }

    private static int count(DevApprovalBatch batch, DevApprovalStatus status) {
        int count = 0;
        for (DevApprovalItem item : batch.items()) {
            if (item.status() == status) {
                count++;
            }
        }
        return count;
    }

    private void audit(
            String action,
            DevApprovalBatch batch,
            Map<String, Object> before,
            Map<String, Object> after) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(action, "dev_approval_batch", batch.batchId(), before, after);
        } catch (RuntimeException e) {
            log.warn("[G2] 记录审计失败：batchId={}，原因={}", batch.batchId(), e.getMessage());
        }
    }

    private static Map<String, Object> snapshot(DevApprovalBatch batch) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("plan_id", batch.planId());
        snapshot.put("status", batch.status().wireName());
        snapshot.put("summary", batch.summary());
        snapshot.put("decided_by", batch.decidedBy());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DevApprovalItem item : batch.items()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("task_id", item.taskId());
            value.put("artifact_type", item.artifactType());
            value.put("version", item.version());
            value.put("diff_hash", item.diffHash());
            value.put("status", item.status().wireName());
            items.add(value);
        }
        snapshot.put("items", items);
        return snapshot;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
