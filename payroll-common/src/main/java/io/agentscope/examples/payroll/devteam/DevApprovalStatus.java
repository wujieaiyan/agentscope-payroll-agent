package io.agentscope.examples.payroll.devteam;

import java.util.Locale;
import java.util.Optional;

/** 任务级审批（G2）批次与条目的状态。 */
public enum DevApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    STALE("stale");

    private final String wireName;

    DevApprovalStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<DevApprovalStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DevApprovalStatus status : values()) {
            if (status.wireName.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
