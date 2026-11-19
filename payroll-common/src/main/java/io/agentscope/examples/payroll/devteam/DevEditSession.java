package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单个研发任务的编辑会话（P1-1）：结构化编辑只落在隔离工作区，任务收尾统一生成
 * 基线 diff 并作为补丁产物入库，供 G2 批量确认与后续审查使用。
 *
 * <p>一次会话只对应一个任务的单类补丁（后端/前端/数据库/运维）；快照前的所有编辑
 * 都可通过重复快照重新取 diff，{@link DevArtifactStore} 会按内容去重，不会产生噪声版本。
 */
public final class DevEditSession {

    private final String taskId;
    private final DevArtifactType patchType;
    private final String owner;
    private final DevSandboxEditor editor;
    private final DevWorkspace workspace;
    private final DevArtifactStore artifacts;
    private final AuditService audit;
    private final Set<String> touched = new LinkedHashSet<>();
    private DevArtifact lastSnapshot;

    public DevEditSession(
            String taskId,
            DevArtifactType patchType,
            String owner,
            String role,
            DevWorkspace workspace,
            DevArtifactStore artifacts,
            AuditService audit) {
        if (taskId == null || taskId.isBlank()) {
            throw new PayrollException("编辑会话缺少任务 ID");
        }
        if (patchType == null || !patchType.patch()) {
            throw new PayrollException("编辑会话只能产出补丁类产物");
        }
        if (owner == null || owner.isBlank()) {
            throw new PayrollException("编辑会话缺少 owner");
        }
        if (workspace == null) {
            throw new PayrollException("编辑会话缺少隔离工作区");
        }
        if (artifacts == null) {
            throw new PayrollException("编辑会话缺少产物库");
        }
        this.taskId = taskId.trim();
        this.patchType = patchType;
        this.owner = owner.trim();
        this.workspace = workspace;
        this.artifacts = artifacts;
        this.audit = audit;
        this.editor = new DevSandboxEditor(role, workspace.root());
    }

    public String taskId() {
        return taskId;
    }

    public DevArtifactType patchType() {
        return patchType;
    }

    public DevSandboxEditor editor() {
        return editor;
    }

    public DevWorkspace workspace() {
        return workspace;
    }

    public synchronized List<String> touchedFiles() {
        return List.copyOf(touched);
    }

    public synchronized Optional<DevArtifact> lastSnapshot() {
        return Optional.ofNullable(lastSnapshot);
    }

    public synchronized DevSandboxEditor.Outcome apply(DevEdit edit) {
        if (edit == null) {
            throw new PayrollException("编辑内容不能为空");
        }
        String relative = editor.normalize(edit.path());
        DevSandboxEditor.Outcome outcome = editor.apply(edit);
        if (outcome.change() != DevSandboxEditor.Change.UNCHANGED) {
            touched.add(outcome.path());
        }
        if (audit != null) {
            audit.record(
                    "devteam.edit",
                    "dev-task",
                    taskId,
                    Map.of("path", relative, "beforeHash", outcome.beforeHash()),
                    Map.of(
                            "path", outcome.path(),
                            "afterHash", outcome.afterHash(),
                            "change", outcome.change().name(),
                            "artifactType", patchType.id()));
        }
        return outcome;
    }

    public synchronized List<DevSandboxEditor.Outcome> applyAll(List<DevEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            throw new PayrollException("没有可应用的编辑");
        }
        List<DevSandboxEditor.Outcome> outcomes = new ArrayList<>(edits.size());
        for (DevEdit edit : edits) {
            outcomes.add(apply(edit));
        }
        return List.copyOf(outcomes);
    }

    /** 取工作区 diff 并以补丁产物落库；diff 为空时拒绝，避免登记空补丁。 */
    public synchronized DevArtifact snapshot(String title) {
        if (touched.isEmpty()) {
            throw new PayrollException("任务 " + taskId + " 尚无编辑，无法生成补丁");
        }
        String diff = workspace.diff();
        if (diff.isBlank()) {
            throw new PayrollException("工作区没有可提交的差异：" + taskId);
        }
        List<DevSecretScanner.Finding> secrets = DevSecretScanner.scan(diff);
        if (!secrets.isEmpty()) {
            String summary =
                    String.join(
                            "；",
                            secrets.stream()
                                    .limit(3)
                                    .map(DevSecretScanner.Finding::describe)
                                    .toList());
            if (audit != null) {
                audit.record(
                        "devteam.patch.rejected",
                        "dev-task",
                        taskId,
                        null,
                        Map.of(
                                "reason", "secret",
                                "artifactType", patchType.id(),
                                "findings", summary));
            }
            throw new PayrollException(
                    "补丁包含疑似明文凭据，已拒绝：" + summary + "；请改用环境变量或占位符");
        }
        DevArtifact previous = lastSnapshot;
        DevArtifact artifact =
                artifacts.save(taskId, patchType, owner, title, diff, null);
        lastSnapshot = artifact;
        if (audit != null) {
            audit.record(
                    "devteam.patch.snapshot",
                    "dev-task",
                    taskId,
                    previous == null ? null : snapshotView(previous),
                    snapshotView(artifact));
        }
        return artifact;
    }

    private static Map<String, Object> snapshotView(DevArtifact artifact) {
        return Map.of(
                "artifactId", artifact.artifactId(),
                "artifactType", artifact.artifactType(),
                "version", artifact.version(),
                "diffHash", artifact.diffHash() == null ? "" : artifact.diffHash(),
                "sizeBytes", artifact.sizeBytes());
    }
}
