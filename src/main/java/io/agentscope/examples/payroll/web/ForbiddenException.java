package io.agentscope.examples.payroll.web;

/** 已登录但权限不足，统一映射为 HTTP 403。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
