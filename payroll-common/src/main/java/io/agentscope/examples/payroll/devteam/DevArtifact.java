package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;

/**
 * 研发域产物的一份不可变版本。
 *
 * <p>{@code (taskId, artifactType)} 内版本号递增；补丁类产物由 {@link DevArtifactStore}
 * 保证 {@code diffHash} 非空。{@link #relativePath()} 给出产物库约定的落盘位置，
 * 数据库记录始终是唯一事实源。
 */
public record DevArtifact(
        long artifactId,
        String taskId,
        String artifactType,
        int version,
        String owner,
        String title,
        String content,
        String contentHash,
        String diffHash,
        long sizeBytes,
        LocalDateTime createdAt) {

    public String fileName() {
        return DevArtifactType.parse(artifactType)
                .map(DevArtifactType::fileName)
                .orElse(artifactType + ".txt");
    }

    public String relativePath() {
        return "dev_tasks/" + taskId + "/v" + version + "/" + fileName();
    }
}
