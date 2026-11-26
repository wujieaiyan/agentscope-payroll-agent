package io.agentscope.examples.payroll.kb.parse;

/** parser 内部接口的错误响应体；retryable 供调用方决定是否退避重试。 */
public record KbParseError(String code, String message, boolean retryable) {
}
