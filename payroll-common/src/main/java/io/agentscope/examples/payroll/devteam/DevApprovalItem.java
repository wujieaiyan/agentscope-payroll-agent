package io.agentscope.examples.payroll.devteam;

/**
 * 任务级审批条目（P0-8）：一个任务的一份补丁，审批以 diff hash 锚定用户看到的版本。
 *
 * <p>审批前会重新读取产物库最新补丁；hash 不一致说明补丁已被改写，条目置为
 * {@link DevApprovalStatus#STALE}，不允许在用户未见过的内容上放行。
 */
public record DevApprovalItem(
        String taskId,
        String artifactType,
        long artifactId,
        int version,
        String diffHash,
        DevApprovalStatus status,
        String reason) {

    public DevApprovalItem {
        taskId = taskId == null ? "" : taskId.trim();
        artifactType = artifactType == null ? "" : artifactType.trim();
        diffHash = diffHash == null ? "" : diffHash.trim();
        status = status == null ? DevApprovalStatus.PENDING : status;
        reason = reason == null ? "" : reason.trim();
    }

    public static DevApprovalItem pending(DevArtifact artifact) {
        return new DevApprovalItem(
                artifact.taskId(),
                artifact.artifactType(),
                artifact.artifactId(),
                artifact.version(),
                artifact.diffHash(),
                DevApprovalStatus.PENDING,
                "");
    }

    public DevApprovalItem decide(DevApprovalStatus next, String note) {
        return new DevApprovalItem(
                taskId, artifactType, artifactId, version, diffHash, next, note);
    }

    public boolean pending() {
        return status == DevApprovalStatus.PENDING;
    }
}
