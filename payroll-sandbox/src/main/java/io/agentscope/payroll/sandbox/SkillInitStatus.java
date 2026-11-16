package io.agentscope.payroll.sandbox;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 工作区状态：既有持久化状态（INITIALIZING/READY/FAILED/UNKNOWN），
 * 也有传输层状态（BUSY/NOT_CONFIGURED/INVALID），后者只出现在 HTTP 响应中。
 *
 * <p>{@code .init.json} 与响应体都使用 snake_case，与运行期执行契约保持一致。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillInitStatus(
        String status,
        String skillId,
        String setupPath,
        Integer exitCode,
        long startedAt,
        long finishedAt,
        long sizeBytes,
        String logTail,
        String message) {

    public static final String INITIALIZING = "INITIALIZING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String BUSY = "BUSY";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String INVALID = "INVALID";

    public static SkillInitStatus initializing(
            String skillId, String setupPath, long startedAt) {
        return new SkillInitStatus(
                INITIALIZING, skillId, setupPath, null, startedAt, 0, 0, null, "初始化进行中");
    }

    public static SkillInitStatus unknown(String skillId) {
        return new SkillInitStatus(
                UNKNOWN, skillId, null, null, 0, 0, 0, null, "技能数据未初始化");
    }

    public static SkillInitStatus busy(String skillId, String message) {
        return new SkillInitStatus(BUSY, skillId, null, null, 0, 0, 0, null, message);
    }

    public static SkillInitStatus notConfigured(String message) {
        return new SkillInitStatus(
                NOT_CONFIGURED, null, null, null, 0, 0, 0, null, message);
    }

    public static SkillInitStatus invalid(String message) {
        return new SkillInitStatus(INVALID, null, null, null, 0, 0, 0, null, message);
    }

    public boolean ready() {
        return READY.equals(status);
    }
}
