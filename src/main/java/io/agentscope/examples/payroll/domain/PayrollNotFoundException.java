package io.agentscope.examples.payroll.domain;

/** 资源不存在或无权查看；对外统一映射为 404，避免泄露他人资源是否存在。 */
public final class PayrollNotFoundException extends PayrollException {

    public PayrollNotFoundException(String message) {
        super(message);
    }
}
