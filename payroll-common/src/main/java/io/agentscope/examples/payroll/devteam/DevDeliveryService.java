package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevDeliveryStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G3 交付（P2-1）：把 G2 已放行的补丁冻结成一次交付申请，人工确认后在本地仓库
 * 建分支并提交，同时生成 PR 描述与回滚步骤。
 *
 * <p>边界：只在本地创建分支与 commit，绝不推送；执行前逐条核对补丁版本与 diff hash，
 * 漂移的申请置为 {@link DevDeliveryStatus#STALE}；交付前要求每份补丁都有合格的审查报告。
 */
public final class DevDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DevDeliveryService.class);
    private static final List<DevArtifactType> PATCH_TYPES =
            List.of(
                    DevArtifactType.PATCH_BACKEND,
                    DevArtifactType.PATCH_FRONTEND,
                    DevArtifactType.PATCH_DB,
                    DevArtifactType.PATCH_DEVOPS);
    private static final Map<String, String> TYPE_LABELS =
            Map.of(
                    DevArtifactType.PATCH_BACKEND.id(), "后端",
                    DevArtifactType.PATCH_FRONTEND.id(), "前端",
                    DevArtifactType.PATCH_DB.id(), "数据库",
                    DevArtifactType.PATCH_DEVOPS.id(), "运维");
    private static final String STALE_NOTE = "补丁在 G3 确认期间已更新，请重新打开审批";
    private static final String ROLLBACK_MARK = "<commit>";
    private static final int MAX_SUMMARY_CHARS = 160;

    private final DevTaskStore tasks;
    private final DevArtifactStore artifacts;
    private final DevApprovalService approvals;
    private final DevDeliveryStore deliveries;
    private final Path projectRoot;
    private final Path workRoot;
    private final AuditService audit;

    public DevDeliveryService(
            DevTaskStore tasks,
            DevArtifactStore artifacts,
            DevApprovalService approvals,
            DevDeliveryStore deliveries,
            Path projectRoot,
            Path workRoot,
            AuditService audit) {
        if (tasks == null || artifacts == null || approvals == null || deliveries == null) {
            throw new IllegalArgumentException("交付服务依赖不能为空");
        }
        if (projectRoot == null || workRoot == null) {
            throw new IllegalArgumentException("交付需要仓库根与工作区根目录");
        }
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.approvals = approvals;
        this.deliveries = deliveries;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.workRoot = workRoot.toAbsolutePath().normalize();
        this.audit = audit;
    }

    /** 冻结当前补丁并生成 G3 待确认申请；要求 G2 已通过且审查报告无阻断。 */
    public DevDelivery open(String planId, String branch) {
        String branchName = DevDeliveryStore.requireBranch(branch);
        Path project = requireProject();
        List<DevTask> planTasks = tasks.list(planId);
        if (planTasks.isEmpty()) {
            throw new PayrollException("计划没有任务，不能交付：" + planId);
        }
        List<DevDeliveryPatch> patches = new ArrayList<>();
        Set<String> patchTaskIds = new LinkedHashSet<>();
        for (DevTask task : planTasks) {
            for (DevArtifactType type : PATCH_TYPES) {
                Optional<DevArtifact> latest = artifacts.latest(task.taskId(), type);
                if (latest.isPresent()) {
                    DevArtifact artifact = latest.get();
                    patches.add(
                            new DevDeliveryPatch(
                                    task.taskId(),
                                    type.id(),
                                    artifact.version(),
                                    artifact.diffHash()));
                    patchTaskIds.add(task.taskId());
                }
            }
        }
        if (patches.isEmpty()) {
            throw new PayrollException("计划没有可交付的补丁：" + planId);
        }
        String batchId = requireApproved(planId, patches);
        Map<String, String> reviews = requireReviews(patchTaskIds);
        Map<String, String> tests = testSummaries(planTasks);
        String baseCommit = headCommit(project);
        requireBranchFree(project, branchName);
        String title = prTitle(planId, patches);
        String body = prBody(planId, branchName, baseCommit, batchId, patches, reviews, tests);
        DevDelivery delivery = deliveries.create(planId, branchName, baseCommit, title, body, patches);
        audit("devteam.g3.open", delivery, null, snapshot(delivery));
        log.info(
                "[G3] 打开交付申请：deliveryId={}，planId={}，branch={}，补丁={}",
                delivery.deliveryId(),
                planId,
                branchName,
                patches.size());
        return delivery;
    }

    public Optional<DevDelivery> find(String deliveryId) {
        return deliveries.find(deliveryId);
    }

    public List<DevDelivery> list(String planId) {
        return deliveries.list(planId);
    }

    /** G3 通过：核对补丁未漂移后建分支并提交；重复确认返回当前状态。 */
    public DevDelivery approve(String deliveryId, String actor) {
        return approve(deliveryId, actor, "");
    }

    public DevDelivery approve(String deliveryId, String actor, String note) {
        DevDelivery delivery = require(deliveryId);
        if (!delivery.pending()) {
            return delivery;
        }
        List<DevArtifact> contents = new ArrayList<>();
        for (DevDeliveryPatch patch : delivery.patches()) {
            DevArtifactType type =
                    DevArtifactType.parse(patch.artifactType())
                            .orElseThrow(
                                    () ->
                                            new PayrollException(
                                                    "交付补丁类型非法：" + patch.artifactType()));
            Optional<DevArtifact> latest = artifacts.latest(patch.taskId(), type);
            if (latest.isEmpty() || !patch.diffHash().equals(latest.get().diffHash())) {
                return stale(delivery, actor);
            }
            contents.add(
                    artifacts.find(patch.taskId(), type, patch.version()).orElse(latest.get()));
        }
        String commitId = commit(delivery, contents, actor);
        if (!deliveries.decide(
                delivery.deliveryId(), DevDeliveryStatus.APPROVED, commitId, actor, note)) {
            log.info("[G3] 执行竞争失败，返回当前状态：deliveryId={}", deliveryId);
            return require(deliveryId);
        }
        DevDelivery result = require(deliveryId);
        audit("devteam.g3.approve", result, snapshot(delivery), snapshot(result));
        log.info(
                "[G3] 交付完成：deliveryId={}，branch={}，commit={}，操作人={}",
                deliveryId,
                result.branch(),
                commitId,
                actor);
        return result;
    }

    /** G3 驳回：不建分支、不提交。已决策的申请直接返回当前状态。 */
    public DevDelivery reject(String deliveryId, String actor, String note) {
        DevDelivery delivery = require(deliveryId);
        if (!delivery.pending()) {
            return delivery;
        }
        deliveries.decide(delivery.deliveryId(), DevDeliveryStatus.REJECTED, "", actor, note);
        DevDelivery result = require(deliveryId);
        audit("devteam.g3.reject", result, snapshot(delivery), snapshot(result));
        log.info("[G3] 交付已驳回：deliveryId={}，操作人={}", deliveryId, actor);
        return result;
    }

    private DevDelivery stale(DevDelivery delivery, String actor) {
        deliveries.decide(
                delivery.deliveryId(), DevDeliveryStatus.STALE, "", actor, STALE_NOTE);
        DevDelivery result = require(delivery.deliveryId());
        audit("devteam.g3.stale", result, snapshot(delivery), snapshot(result));
        log.warn("[G3] 补丁已漂移，拒绝交付：deliveryId={}", delivery.deliveryId());
        return result;
    }

    private String requireApproved(String planId, List<DevDeliveryPatch> patches) {
        List<DevApprovalBatch> batches = approvals.list(planId);
        if (batches.isEmpty()) {
            throw new PayrollException("尚未完成 G2 审批，不能交付：" + planId);
        }
        for (DevApprovalBatch batch : batches) {
            if (batch.pending()) {
                throw new PayrollException("存在未完成的 G2 审批批次，不能交付：" + batch.batchId());
            }
        }
        DevApprovalBatch approved = batches.get(0);
        if (approved.status() != DevApprovalStatus.APPROVED) {
            throw new PayrollException(
                    "G2 审批未通过（" + approved.status().wireName() + "），不能交付：" + planId);
        }
        Map<String, DevApprovalItem> items = new LinkedHashMap<>();
        for (DevApprovalItem item : approved.items()) {
            items.put(item.taskId() + "|" + item.artifactType(), item);
        }
        for (DevDeliveryPatch patch : patches) {
            DevApprovalItem item = items.get(patch.key());
            if (item == null || item.status() != DevApprovalStatus.APPROVED) {
                throw new PayrollException(
                        "补丁未经 G2 放行：" + patch.taskId() + "/" + patch.artifactType());
            }
            if (!item.diffHash().equals(patch.diffHash())) {
                throw new PayrollException(
                        "补丁在 G2 后已更新：" + patch.taskId() + "/" + patch.artifactType());
            }
        }
        return approved.batchId();
    }

    private Map<String, String> requireReviews(Set<String> patchTaskIds) {
        Map<String, String> summaries = new LinkedHashMap<>();
        for (String taskId : patchTaskIds) {
            Optional<DevArtifact> latest =
                    artifacts.latest(taskId, DevArtifactType.REVIEW_REPORT);
            if (latest.isEmpty()) {
                throw new PayrollException("交付前缺少审查报告：" + taskId);
            }
            DevReviewReport report = DevReviewReport.parse(latest.get().content());
            if (!report.violations().isEmpty()) {
                throw new PayrollException(
                        "审查报告不完整，不能交付：" + taskId + "（" + String.join("；", report.violations()) + "）");
            }
            if (!report.blocking().isEmpty()) {
                throw new PayrollException(
                        "存在未关闭的审查阻断项，不能交付："
                                + taskId
                                + "（"
                                + report.blockingSummary()
                                + "）");
            }
            summaries.put(taskId, report.summary());
        }
        return summaries;
    }

    private Map<String, String> testSummaries(List<DevTask> planTasks) {
        Map<String, String> summaries = new LinkedHashMap<>();
        for (DevTask task : planTasks) {
            artifacts
                    .latest(task.taskId(), DevArtifactType.TEST_REPORT)
                    .map(DevArtifact::content)
                    .flatMap(content -> firstLine(content))
                    .ifPresent(line -> summaries.put(task.taskId(), line));
        }
        return summaries;
    }

    private static Optional<String> firstLine(String content) {
        if (content == null) {
            return Optional.empty();
        }
        for (String line : content.split("\\R")) {
            String value = line.strip();
            if (!value.isEmpty()) {
                return Optional.of(
                        value.length() > MAX_SUMMARY_CHARS
                                ? value.substring(0, MAX_SUMMARY_CHARS)
                                : value);
            }
        }
        return Optional.empty();
    }

    private String commit(DevDelivery delivery, List<DevArtifact> patches, String actor) {
        Path project = requireProject();
        Path dir = workRoot.resolve("delivery-" + delivery.deliveryId());
        try {
            Files.createDirectories(workRoot);
            if (Files.exists(dir)) {
                throw new PayrollException("交付工作目录已存在：" + dir);
            }
            try (Git clone =
                    Git.cloneRepository()
                            .setURI(project.toUri().toString())
                            .setDirectory(dir.toFile())
                            .setCloneAllBranches(false)
                            .call()) {
                clone.checkout().setCreateBranch(true).setName(delivery.branch()).call();
                for (DevArtifact patch : patches) {
                    if (patch.content() == null || patch.content().isBlank()) {
                        continue;
                    }
                    try (InputStream input =
                            new ByteArrayInputStream(
                                    patch.content().getBytes(StandardCharsets.UTF_8))) {
                        clone.apply().setPatch(input).call();
                    }
                }
                clone.add().addFilepattern(".").call();
                clone.add().setUpdate(true).addFilepattern(".").call();
                PersonIdent identity = identity(actor);
                RevCommit commit =
                        clone.commit()
                                .setMessage(commitMessage(delivery))
                                .setAuthor(identity)
                                .setCommitter(identity)
                                .setAllowEmpty(false)
                                .setNoVerify(true)
                                .call();
                fetchBranch(project, clone, delivery.branch());
                return commit.getName();
            }
        } catch (PayrollException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new PayrollException(
                    "创建交付分支失败：" + delivery.branch() + "，原因：" + e.getMessage(), e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void fetchBranch(Path project, Git source, String branch)
            throws IOException, GitAPIException {
        try (Git target = Git.open(project.toFile())) {
            String remote = source.getRepository().getDirectory().toURI().toString();
            target.fetch()
                    .setRemote(remote)
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .call();
        }
    }

    private static PersonIdent identity(String actor) {
        String name = actor == null ? "" : actor.trim().replaceAll("[<>\\r\\n]", "");
        if (name.isEmpty()) {
            name = "dev-team";
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return new PersonIdent(name, name + "@dev-team.local");
    }

    private static String commitMessage(DevDelivery delivery) {
        return "交付 "
                + delivery.planId()
                + "："
                + delivery.patches().size()
                + " 份补丁（G3 已确认）\n\n"
                + "delivery: "
                + delivery.deliveryId()
                + "\nbase: "
                + delivery.baseCommit();
    }

    private static String prTitle(String planId, List<DevDeliveryPatch> patches) {
        String value = "[dev-team] " + planId + " 交付：" + patches.size() + " 份补丁";
        return value.length() > DevDeliveryStore.MAX_TITLE
                ? value.substring(0, DevDeliveryStore.MAX_TITLE)
                : value;
    }

    private static String prBody(
            String planId,
            String branch,
            String baseCommit,
            String batchId,
            List<DevDeliveryPatch> patches,
            Map<String, String> reviews,
            Map<String, String> tests) {
        StringBuilder body = new StringBuilder();
        body.append("## 变更摘要\n")
                .append("- 计划：").append(planId).append('\n')
                .append("- 交付分支：").append(branch).append("（基于 ").append(baseCommit).append("）\n")
                .append("- 补丁：").append(patches.size()).append(" 份\n")
                .append("- G2 审批批次：").append(batchId).append('\n')
                .append("\n### 补丁清单\n");
        for (DevDeliveryPatch patch : patches) {
            body.append("- ")
                    .append(patch.taskId())
                    .append("：")
                    .append(TYPE_LABELS.getOrDefault(patch.artifactType(), patch.artifactType()))
                    .append(' ')
                    .append(patch.artifactType())
                    .append(" v")
                    .append(patch.version())
                    .append("（diff hash ")
                    .append(shortHash(patch.diffHash()))
                    .append("）\n");
        }
        body.append("\n### 审查结论\n");
        if (reviews.isEmpty()) {
            body.append("- 无\n");
        }
        for (Map.Entry<String, String> entry : reviews.entrySet()) {
            body.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append('\n');
        }
        body.append("\n### 测试结果\n");
        if (tests.isEmpty()) {
            body.append("- 未提供测试报告\n");
        }
        for (Map.Entry<String, String> entry : tests.entrySet()) {
            body.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append('\n');
        }
        body.append("\n### 回滚步骤\n")
                .append("- 交付前：`git branch -D ").append(branch).append("`\n")
                .append("- 交付后：`git revert ")
                .append(ROLLBACK_MARK)
                .append("`（执行后 commit id 写入交付记录）\n")
                .append("- 补丁版本与 hash 以 G2 批次 ").append(batchId).append(" 为锚点\n")
                .append("\n> 本交付只在本地创建分支与 commit，未推送；推送由宿主凭证代理在人工确认后执行。\n");
        return body.toString();
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "未知";
        }
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    private Path requireProject() {
        if (!Files.isDirectory(projectRoot.resolve(".git"))) {
            throw new PayrollException("交付仓库根不是 Git 仓库：" + projectRoot);
        }
        return projectRoot;
    }

    private static String headCommit(Path project) {
        try (Git git = Git.open(project.toFile())) {
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve("HEAD");
            if (head == null) {
                throw new PayrollException("交付仓库没有 HEAD 提交：" + project);
            }
            return head.name();
        } catch (IOException e) {
            throw new PayrollException("读取交付基线失败：" + project + "，原因：" + e.getMessage(), e);
        }
    }

    private static void requireBranchFree(Path project, String branch) {
        try (Git git = Git.open(project.toFile())) {
            if (git.getRepository().exactRef("refs/heads/" + branch) != null) {
                throw new PayrollException("交付分支已存在：" + branch);
            }
        } catch (IOException e) {
            throw new PayrollException("检查交付分支失败：" + branch + "，原因：" + e.getMessage(), e);
        }
    }

    private DevDelivery require(String deliveryId) {
        return deliveries
                .find(deliveryId)
                .orElseThrow(() -> new PayrollNotFoundException("交付申请不存在"));
    }

    private void audit(
            String action,
            DevDelivery delivery,
            Map<String, Object> before,
            Map<String, Object> after) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(action, "dev_delivery", delivery.deliveryId(), before, after);
        } catch (RuntimeException e) {
            log.warn("[G3] 记录审计失败：deliveryId={}，原因={}", delivery.deliveryId(), e.getMessage());
        }
    }

    private static Map<String, Object> snapshot(DevDelivery delivery) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("plan_id", delivery.planId());
        snapshot.put("branch", delivery.branch());
        snapshot.put("base_commit", delivery.baseCommit());
        snapshot.put("status", delivery.status().wireName());
        snapshot.put("commit_id", delivery.commitId());
        snapshot.put("decided_by", delivery.decidedBy());
        List<Map<String, Object>> patches = new ArrayList<>();
        for (DevDeliveryPatch patch : delivery.patches()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("task_id", patch.taskId());
            value.put("artifact_type", patch.artifactType());
            value.put("version", patch.version());
            value.put("diff_hash", patch.diffHash());
            patches.add(value);
        }
        snapshot.put("patches", patches);
        return snapshot;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(
                    path,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            deleteFile(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                                throws IOException {
                            deleteFile(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("[G3] 清理交付工作区失败：{}，原因={}", path, e.getMessage());
        }
    }

    private static void deleteFile(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (java.nio.file.AccessDeniedException e) {
            if (path.toFile().setWritable(true)) {
                Files.deleteIfExists(path);
            } else {
                throw e;
            }
        }
    }
}
