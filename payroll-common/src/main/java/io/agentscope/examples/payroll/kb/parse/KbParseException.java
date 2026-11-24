package io.agentscope.examples.payroll.kb.parse;

import io.agentscope.examples.payroll.domain.PayrollException;

/** 解析服务不可用或返回不可预期结果时的可展示异常。 */
public class KbParseException extends PayrollException {

    public KbParseException(String message) {
        super(message);
    }

    public KbParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
