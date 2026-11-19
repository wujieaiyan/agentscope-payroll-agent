package io.agentscope.examples.payroll.model;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 页面录入密钥的加解密：AES-256-GCM，12 字节随机 nonce，AAD 固定为连接 ID，
 * 防止密文跨连接搬运。主密钥来自 {@code PAYROLL_SECRET_KEY}（Base64 的 32 字节）。
 */
public final class ModelCredentialCipher {

    public static final String ENV_SECRET_KEY = "PAYROLL_SECRET_KEY";

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String unavailableReason;

    private ModelCredentialCipher(SecretKey key, String unavailableReason) {
        this.key = key;
        this.unavailableReason = unavailableReason;
    }

    public static ModelCredentialCipher fromEnvironment() {
        String value = System.getenv(ENV_SECRET_KEY);
        if (value == null || value.isBlank()) {
            return new ModelCredentialCipher(null, "未配置 " + ENV_SECRET_KEY);
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value.trim());
            if (raw.length != KEY_BYTES) {
                return new ModelCredentialCipher(
                        null, ENV_SECRET_KEY + " 必须是 Base64 编码的 32 字节密钥");
            }
            return new ModelCredentialCipher(
                    new SecretKeySpec(raw, "AES"), "");
        } catch (IllegalArgumentException e) {
            return new ModelCredentialCipher(
                    null, ENV_SECRET_KEY + " 必须是 Base64 编码的 32 字节密钥");
        }
    }

    public static ModelCredentialCipher of(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("主密钥必须是 32 字节");
        }
        return new ModelCredentialCipher(new SecretKeySpec(rawKey, "AES"), "");
    }

    public boolean available() {
        return key != null;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /** 加密并返回密文与 nonce（两者都是 Base64 文本）。 */
    public SealedCredential encrypt(String connectionId, String plaintext) {
        if (key == null) {
            throw new ModelConfigException(
                    ModelConfigException.SECRET_KEY_MISSING,
                    "未配置可用的 " + ENV_SECRET_KEY + "：" + unavailableReason);
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION, "密钥不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(connectionId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new SealedCredential(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    1);
        } catch (Exception e) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_UNREADABLE,
                    "加密模型密钥失败",
                    e);
        }
    }

    /** 解密；任何失败（主密钥缺失/不匹配、密文被篡改）一律 fail-closed。 */
    public String decrypt(String connectionId, String ciphertext, String nonce) {
        if (key == null) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_UNREADABLE,
                    "无法解密模型密钥：未配置可用的 " + ENV_SECRET_KEY);
        }
        if (ciphertext == null || ciphertext.isBlank() || nonce == null || nonce.isBlank()) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_UNREADABLE, "模型密钥记录不完整");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            cipher.updateAAD(connectionId.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_UNREADABLE,
                    "无法解密模型密钥，请重新录入",
                    e);
        }
    }

    public record SealedCredential(String ciphertext, String nonce, int keyVersion) {}
}
