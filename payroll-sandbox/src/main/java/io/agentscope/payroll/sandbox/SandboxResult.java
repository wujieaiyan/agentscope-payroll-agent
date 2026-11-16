package io.agentscope.payroll.sandbox;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 执行结果；状态区分正常返回、超时、参数错误、并发繁忙与运行时不可用。
 *
 * <p>响应体固定 snake_case，与 {@code SandboxClient} 的解析字段保持一致；Spring 默认
 * ObjectMapper 不做全局命名策略，不能只依赖请求侧的本地 mapper。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SandboxResult(
        String status,
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        String scriptSha256,
        boolean truncated,
        String message) {

    public static final String OK = "OK";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String ERROR = "ERROR";
    public static final String BUSY = "BUSY";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String INVALID = "INVALID";
    /** 技能声明了初始化脚本，但数据尚未就绪；由调用方提示先在管理台初始化。 */
    public static final String DATA_NOT_READY = "DATA_NOT_READY";

    public static SandboxResult ok(
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            String scriptSha256,
            boolean truncated) {
        return new SandboxResult(
                OK, exitCode, stdout, stderr, durationMs, scriptSha256, truncated, null);
    }

    public static SandboxResult timeout(String scriptSha256, long durationMs) {
        return new SandboxResult(
                TIMEOUT, -1, "", "", durationMs, scriptSha256, false, "执行超时，容器已被强制终止");
    }

    public static SandboxResult unavailable(String message) {
        return new SandboxResult(UNAVAILABLE, -1, "", "", 0, "", false, message);
    }

    public static SandboxResult error(String message) {
        return new SandboxResult(ERROR, -1, "", "", 0, "", false, message);
    }

    public static SandboxResult busy(String message) {
        return new SandboxResult(BUSY, -1, "", "", 0, "", false, message);
    }

    public static SandboxResult invalid(String message) {
        return new SandboxResult(INVALID, -1, "", "", 0, "", false, message);
    }

    public static SandboxResult dataNotReady(String message) {
        return new SandboxResult(DATA_NOT_READY, -1, "", "", 0, "", false, message);
    }
}
