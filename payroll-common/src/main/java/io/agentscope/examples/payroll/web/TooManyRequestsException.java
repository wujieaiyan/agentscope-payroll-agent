package io.agentscope.examples.payroll.web;

/**
 * 触发限流或并发配额时返回 429。
 *
 * <p>响应体统一为 {@code {code, retryAfterSeconds, message}}，前端据此倒计时，
 * 避免在容量恢复前反复重试；{@code code} 目前只有 {@code BUSY}。
 */
public class TooManyRequestsException extends RuntimeException {

    public static final String CODE_BUSY = "BUSY";
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;

    private final String code;
    private final int retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(CODE_BUSY, DEFAULT_RETRY_AFTER_SECONDS, message);
    }

    public TooManyRequestsException(String message, int retryAfterSeconds) {
        this(CODE_BUSY, retryAfterSeconds, message);
    }

    public TooManyRequestsException(String code, int retryAfterSeconds, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? CODE_BUSY : code.trim();
        this.retryAfterSeconds =
                retryAfterSeconds <= 0 ? DEFAULT_RETRY_AFTER_SECONDS : retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
