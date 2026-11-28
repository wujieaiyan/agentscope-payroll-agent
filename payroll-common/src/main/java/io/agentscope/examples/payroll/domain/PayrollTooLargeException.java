package io.agentscope.examples.payroll.domain;

/** 上传内容超过单文件或技能级容量上限（HTTP 413）。 */
public class PayrollTooLargeException extends PayrollException {

    public PayrollTooLargeException(String message) {
        super(message);
    }
}
