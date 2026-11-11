package io.agentscope.examples.payroll.domain;

/** 资源已过期（例如待确认操作超过有效期），对外映射为 410。 */
public final class PayrollGoneException extends PayrollException {

    public PayrollGoneException(String message) {
        super(message);
    }
}
