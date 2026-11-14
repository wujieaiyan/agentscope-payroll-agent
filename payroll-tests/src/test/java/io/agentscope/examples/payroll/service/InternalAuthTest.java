package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InternalAuthTest {

    private static final String TOKEN = "unit-test-token";

    @Test
    @DisplayName("正确签名通过校验，大小写不敏感")
    void acceptsValidSignature() {
        String timestamp = now();
        String signature = InternalAuth.sign(TOKEN, "POST", "/internal/turns", timestamp, "{}");

        assertTrue(
                InternalAuth.verify(
                        TOKEN, "POST", "/internal/turns", timestamp, signature, "{}", 120));
        assertTrue(
                InternalAuth.verify(
                        TOKEN,
                        "POST",
                        "/internal/turns",
                        timestamp,
                        signature.toUpperCase(),
                        "{}",
                        120));
    }

    @Test
    @DisplayName("请求体、路径或令牌不符时拒绝")
    void rejectsTamperedRequest() {
        String timestamp = now();
        String signature = InternalAuth.sign(TOKEN, "POST", "/internal/turns", timestamp, "{}");

        assertFalse(
                InternalAuth.verify(
                        TOKEN, "POST", "/internal/turns", timestamp, signature, "{\"a\":1}", 120));
        assertFalse(
                InternalAuth.verify(
                        "other", "POST", "/internal/turns", timestamp, signature, "{}", 120));
        assertFalse(
                InternalAuth.verify(
                        TOKEN, "POST", "/internal/cancel", timestamp, signature, "{}", 120));
        assertFalse(
                InternalAuth.verify(TOKEN, "POST", "/internal/turns", timestamp, "", "{}", 120));
    }

    @Test
    @DisplayName("时间戳超出窗口或格式错误时拒绝")
    void rejectsStaleOrMalformedTimestamp() {
        String stale = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String signature = InternalAuth.sign(TOKEN, "GET", "/internal/health", stale, "");

        assertFalse(
                InternalAuth.verify(
                        TOKEN, "GET", "/internal/health", stale, signature, "", 120));
        assertFalse(
                InternalAuth.verify(TOKEN, "GET", "/internal/health", "abc", signature, "", 120));
        assertFalse(
                InternalAuth.verify(TOKEN, "GET", "/internal/health", now(), null, "", 120));
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }
}
