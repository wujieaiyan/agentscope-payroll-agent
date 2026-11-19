package io.agentscope.examples.payroll.devteam;

import java.util.Locale;
import java.util.Optional;

/**
 * 研发域产物类型（P0-5）：需求、架构、任务、四类补丁、测试与审查、回滚手册。
 *
 * <p>id 用于落库与接口参数，文件名用于 {@code dev_tasks/<task_id>/} 下的磁盘布局；
 * 补丁类型额外要求记录 diff hash，便于 G2 核对用户看到的内容与审批内容一致。
 */
public enum DevArtifactType {
    REQUIREMENTS("requirements", "requirements.md"),
    PRD("prd", "prd.md"),
    ARCHITECTURE("architecture", "architecture.md"),
    INTERFACES("interfaces", "interfaces.md"),
    TASKS("tasks", "tasks.json"),
    DELIVERY_PLAN("delivery-plan", "delivery-plan.md"),
    PATCH_BACKEND("patch-backend", "patch-backend.diff"),
    PATCH_FRONTEND("patch-frontend", "patch-frontend.diff"),
    PATCH_DB("patch-db", "patch-db.diff"),
    PATCH_DEVOPS("patch-devops", "patch-devops.diff"),
    TEST_PLAN("test-plan", "test-plan.md"),
    TEST_REPORT("test-report", "test-report.md"),
    REVIEW_REPORT("review-report", "review-report.md"),
    ROLLBACK("rollback", "rollback.md");

    private final String id;
    private final String fileName;

    DevArtifactType(String id, String fileName) {
        this.id = id;
        this.fileName = fileName;
    }

    public String id() {
        return id;
    }

    public String fileName() {
        return fileName;
    }

    public boolean patch() {
        return id.startsWith("patch-");
    }

    /** 执行角色对应的补丁类型；只读角色（QA/审查/产品等）没有补丁产物。 */
    public static Optional<DevArtifactType> patchForRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case DevPathPolicy.ROLE_IMPLEMENTER -> Optional.of(PATCH_BACKEND);
            case DevPathPolicy.ROLE_FRONTEND -> Optional.of(PATCH_FRONTEND);
            case DevPathPolicy.ROLE_DBA -> Optional.of(PATCH_DB);
            case DevPathPolicy.ROLE_DEVOPS -> Optional.of(PATCH_DEVOPS);
            default -> Optional.empty();
        };
    }

    public static Optional<DevArtifactType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DevArtifactType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
