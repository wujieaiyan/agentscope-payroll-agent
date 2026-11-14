package io.agentscope.examples.payroll.web;

/** 未登录或会话过期，统一映射为 HTTP 401。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
