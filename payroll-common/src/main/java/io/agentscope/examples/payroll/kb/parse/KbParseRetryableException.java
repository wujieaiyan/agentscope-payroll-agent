package io.agentscope.examples.payroll.kb.parse;

/** 解析服务临时不可用（503、超时、连接失败），调用方可退避重试。 */
public class KbParseRetryableException extends KbParseException {

    public KbParseRetryableException(String message) {
        super(message);
    }

    public KbParseRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
