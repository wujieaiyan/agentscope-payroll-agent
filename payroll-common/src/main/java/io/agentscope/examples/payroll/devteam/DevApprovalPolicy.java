package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.Locale;

/** 开发域审批策略：决定「什么时候必须停下来问人」。 */
public enum DevApprovalPolicy {
    /** 全程无人值守，仅 read-only 允许；失败或违规即停。 */
    AUTO("auto"),
    /** 隔离工作区内连续执行至补丁齐备，G2 一次确认全部 diff。 */
    BATCH("batch"),
    /** 高风险任务在 G2 内逐任务确认，其余批量。 */
    PER_TASK("per-task");

    private final String wireName;

    DevApprovalPolicy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static DevApprovalPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return BATCH;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "auto" -> AUTO;
            case "batch" -> BATCH;
            case "per-task", "pertask" -> PER_TASK;
            default ->
                    throw new PayrollException(
                            "未知的开发域审批策略：" + value + "；可取值：auto、batch、per-task");
        };
    }
}
