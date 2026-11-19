package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 研发任务（P0-7）：项目经理面板的一行，包含负责人、依赖、产物与验收口径。
 *
 * <p>{@code taskId} 在单个计划内唯一，来自 {@code tasks.json} 的节点 id；
 * 行主键 {@code id} 由数据库生成。状态变化由 {@link DevTaskStatus} 约束。
 * {@code attempt} 记录人工重试次数，{@code error} 保存最近一次失败原因供任务中心展示。
 */
public record DevTask(
        long id,
        String planId,
        String taskId,
        String title,
        String role,
        String ownerAgent,
        int priority,
        DevTaskStatus status,
        List<String> dependsOn,
        List<String> outputs,
        List<String> acceptance,
        String note,
        int attempt,
        String error,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public DevTask {
        planId = trim(planId);
        taskId = trim(taskId);
        title = trim(title);
        role = trim(role);
        ownerAgent = trim(ownerAgent);
        status = status == null ? DevTaskStatus.PENDING : status;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
        note = trim(note);
        attempt = Math.max(0, attempt);
        error = trim(error);
    }

    /** 新建任务：默认待开始、无负责人以外的进度信息。 */
    public static DevTask pending(
            String planId,
            String taskId,
            String title,
            String role,
            String ownerAgent,
            int priority,
            List<String> dependsOn,
            List<String> outputs,
            List<String> acceptance) {
        return new DevTask(
                0L,
                planId,
                taskId,
                title,
                role,
                ownerAgent,
                priority,
                DevTaskStatus.PENDING,
                dependsOn,
                outputs,
                acceptance,
                "",
                0,
                "",
                null,
                null,
                null,
                null);
    }

    public DevTask withStatus(DevTaskStatus next, String note, LocalDateTime updatedAt) {
        return new DevTask(
                id,
                planId,
                taskId,
                title,
                role,
                ownerAgent,
                priority,
                next,
                dependsOn,
                outputs,
                acceptance,
                note,
                attempt,
                error,
                startedAt,
                finishedAt,
                createdAt,
                updatedAt);
    }

    public DevTask withOwner(String owner, LocalDateTime updatedAt) {
        return new DevTask(
                id,
                planId,
                taskId,
                title,
                role,
                owner,
                priority,
                status,
                dependsOn,
                outputs,
                acceptance,
                note,
                attempt,
                error,
                startedAt,
                finishedAt,
                createdAt,
                updatedAt);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
