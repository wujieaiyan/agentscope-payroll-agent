package io.agentscope.examples.payroll.web;

/** 触发限流或并发配额时返回 429。 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
