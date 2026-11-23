package io.agentscope.examples.payroll.devteam;

import java.util.Locale;
import java.util.Optional;

/** G3 交付审批状态：待确认、已建分支并提交、已驳回、补丁漂移。 */
public enum DevDeliveryStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    STALE("stale");

    private final String wireName;

    DevDeliveryStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<DevDeliveryStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DevDeliveryStatus status : values()) {
            if (status.wireName.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
