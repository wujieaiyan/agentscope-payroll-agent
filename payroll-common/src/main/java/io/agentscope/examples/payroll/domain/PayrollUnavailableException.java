package io.agentscope.examples.payroll.domain;

/** 依赖不可用或读取失败，禁止基于旧数据写入（HTTP 503）。 */
public class PayrollUnavailableException extends RuntimeException {

    public PayrollUnavailableException(String message) {
        super(message);
    }

    public PayrollUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
