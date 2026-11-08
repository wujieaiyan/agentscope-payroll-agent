package io.agentscope.examples.payroll.domain;

/** 批次数据或状态与预期不一致，需要用户刷新后重新操作（HTTP 409）。 */
public class PayrollConflictException extends RuntimeException {

    public PayrollConflictException(String message) {
        super(message);
    }
}
