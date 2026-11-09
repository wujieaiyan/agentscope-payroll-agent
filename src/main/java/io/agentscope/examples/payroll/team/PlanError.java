package io.agentscope.examples.payroll.team;

/** 计划校验错误：结构化返回，供一次修复与审计使用。 */
public record PlanError(String code, String nodeId, String message) {

    public PlanError {
        code = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        nodeId = nodeId == null ? "" : nodeId;
        message = message == null ? "" : message;
    }

    public static PlanError of(String code, String message) {
        return new PlanError(code, "", message);
    }

    public static PlanError at(String code, String nodeId, String message) {
        return new PlanError(code, nodeId, message);
    }

    @Override
    public String toString() {
        return nodeId.isBlank()
                ? code + ": " + message
                : code + "[" + nodeId + "]: " + message;
    }
}
