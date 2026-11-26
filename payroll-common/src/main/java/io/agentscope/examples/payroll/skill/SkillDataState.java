package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.service.SandboxGateway;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 技能数据工作区状态的读模型快照。
 *
 * <p>沙箱是唯一真相，这张快照只服务于列表/概览的批量读：数据页每次查询都会回写，
 * 未打开数据页时由 {@link SkillDataStateRefresher} 在有限条数内收敛。
 * 状态没有 {@code NOT_REQUIRED}——「无需数据」由技能是否声明 setup 派生，不落库。
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillDataState(
        String skillId,
        String status,
        long sizeBytes,
        Integer exitCode,
        long startedAt,
        long finishedAt,
        String message,
        long observedAt) {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String INITIALIZING = "INITIALIZING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    public static final String BUSY = "BUSY";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String INVALID = "INVALID";

    public static SkillDataState unknown(String skillId, long now) {
        return new SkillDataState(skillId, UNKNOWN, 0L, null, 0L, 0L, "", now);
    }

    public static SkillDataState of(String skillId, SandboxGateway.SkillData data, long now) {
        return new SkillDataState(
                skillId,
                data == null || data.status() == null ? UNKNOWN : data.status(),
                data == null ? 0L : data.sizeBytes(),
                data == null ? null : data.exitCode(),
                data == null ? 0L : data.startedAt(),
                data == null ? 0L : data.finishedAt(),
                data == null || data.message() == null ? "" : truncate(data.message()),
                now);
    }

    /** 数据库列宽 512；观测值超长只截断展示，不影响状态本身。 */
    private static String truncate(String message) {
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    public static boolean terminal(String status) {
        return READY.equals(status)
                || FAILED.equals(status)
                || UNAVAILABLE.equals(status)
                || INVALID.equals(status);
    }

    public static boolean pending(String status) {
        return INITIALIZING.equals(status) || BUSY.equals(status);
    }
}
