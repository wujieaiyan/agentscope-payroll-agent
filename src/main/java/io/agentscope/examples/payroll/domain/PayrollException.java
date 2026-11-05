package io.agentscope.examples.payroll.domain;

/** 输入缺失、考勤未就绪等可向用户解释的业务异常。 */
public class PayrollException extends RuntimeException {

    public PayrollException(String message) {
        super(message);
    }

    public PayrollException(String message, Throwable cause) {
        super(message, cause);
    }
}
