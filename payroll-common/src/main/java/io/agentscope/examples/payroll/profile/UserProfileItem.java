package io.agentscope.examples.payroll.profile;

import java.time.LocalDateTime;

/**
 * 用户级工作偏好条目。值必须是受控词表内的组织级事实，不承载金额、人员或证件信息。
 */
public record UserProfileItem(
        long itemId,
        long userId,
        String category,
        String itemKey,
        String itemValue,
        double confidence,
        String source,
        String sourceTurnId,
        String evidence,
        String status,
        int injectedCount,
        LocalDateTime lastInjectedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_MODEL = "MODEL";
    public static final String SOURCE_MANUAL = "MANUAL";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
