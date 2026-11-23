package io.agentscope.examples.payroll.devteam;

import java.util.Locale;
import java.util.Optional;

/**
 * 研发任务状态（P0-7）：待开始 → 依赖就绪 → 执行中 → 完成/失败/阻塞，失败与阻塞可重试。
 *
 * <p>依赖未满足的任务停在 {@link #PENDING}；依赖失败时进入 {@link #BLOCKED}，
 * 依赖恢复后由任务面板重新提升为 {@link #READY}。
 */
public enum DevTaskStatus {
    PENDING("pending"),
    READY("ready"),
    RUNNING("running"),
    BLOCKED("blocked"),
    DONE("done"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireName;

    DevTaskStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean terminal() {
        return this == DONE || this == CANCELLED;
    }

    public boolean failure() {
        return this == FAILED || this == BLOCKED;
    }

    public static Optional<DevTaskStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (DevTaskStatus status : values()) {
            if (status.wireName.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
