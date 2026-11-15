package io.agentscope.examples.payroll.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 内部接口签名：HMAC-SHA256(timestamp + method + path + body)，带时间窗防重放。 */
public final class InternalAuth {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalAuth() {
    }

    public static String sign(
            String token, String method, String path, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest =
                    mac.doFinal(
                            payload(method, path, timestamp, body)
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("内部签名失败：" + e.getMessage(), e);
        }
    }

    public static boolean verify(
            String token,
            String method,
            String path,
            String timestamp,
            String signature,
            String body,
            long maxSkewSeconds) {
        if (token == null || token.isBlank() || signature == null || timestamp == null) {
            return false;
        }
        long epoch;
        try {
            epoch = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - epoch) > Math.max(30, maxSkewSeconds)) {
            return false;
        }
        String expected = sign(token, method, path, timestamp, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String method, String path, String timestamp, String body) {
        return timestamp
                + "\n"
                + method
                + "\n"
                + path
                + "\n"
                + (body == null ? "" : body);
    }
}
