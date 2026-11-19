package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;

/** 研发域护栏拒绝（P0-6/C9）：路径越权、黑名单文件或符号链接逃逸，按类型区分于业务失败。 */
public class DevAccessDeniedException extends PayrollException {

    public DevAccessDeniedException(String message) {
        super(message);
    }

    public DevAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
