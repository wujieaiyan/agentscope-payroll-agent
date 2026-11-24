package io.agentscope.examples.payroll.kb.parse;

/** Web 侧并发准入被拒（等待许可超时）：属于限流，不是解析服务故障。 */
public class KbParseLimitException extends KbParseRetryableException {

    public KbParseLimitException(String message) {
        super(message);
    }

    public KbParseLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
