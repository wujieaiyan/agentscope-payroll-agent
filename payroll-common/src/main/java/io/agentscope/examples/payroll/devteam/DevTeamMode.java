package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.Locale;

/** 开发域沙箱模式：决定「能动什么」，与审批策略正交。不提供 danger-full-access。 */
public enum DevTeamMode {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write");

    private final String wireName;

    DevTeamMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean writable() {
        return this == WORKSPACE_WRITE;
    }

    public static DevTeamMode parse(String value) {
        if (value == null || value.isBlank()) {
            return READ_ONLY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "read-only", "readonly", "ro" -> READ_ONLY;
            case "workspace-write", "workspacewrite", "rw" -> WORKSPACE_WRITE;
            case "danger-full-access", "unrestricted" ->
                    throw new PayrollException(
                            "不提供 danger-full-access / UNRESTRICTED 开发沙箱；"
                                    + "可取值：read-only、workspace-write");
            default ->
                    throw new PayrollException(
                            "未知的开发域模式：" + value + "；可取值：read-only、workspace-write");
        };
    }
}
