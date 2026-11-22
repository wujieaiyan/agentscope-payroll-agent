package io.agentscope.examples.payroll.domain;

/** 批次数据或状态与预期不一致，需要用户刷新后重新操作（HTTP 409）。 */
public class PayrollConflictException extends RuntimeException {

    private final String currentRevision;

    public PayrollConflictException(String message) {
        this(message, null);
    }

    public PayrollConflictException(String message, String currentRevision) {
        super(message);
        this.currentRevision = currentRevision;
    }

    /** 服务端当前配置指纹；未知时为 null。 */
    public String currentRevision() {
        return currentRevision;
    }
}
