package io.agentscope.examples.payroll.domain;

/** 沙箱服务不可达、未启用或内部签名失败；对外统一映射为 503，不回传内部地址与令牌信息。 */
public class SandboxUnavailableException extends RuntimeException {

    public SandboxUnavailableException(String message) {
        super(message);
    }

    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
