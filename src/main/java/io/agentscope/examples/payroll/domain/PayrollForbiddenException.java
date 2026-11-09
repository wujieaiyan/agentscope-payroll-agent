package io.agentscope.examples.payroll.domain;

/** 已登录但无权访问目标资源，统一映射为 HTTP 403。 */
public class PayrollForbiddenException extends RuntimeException {

    public PayrollForbiddenException(String message) {
        super(message);
    }
}
