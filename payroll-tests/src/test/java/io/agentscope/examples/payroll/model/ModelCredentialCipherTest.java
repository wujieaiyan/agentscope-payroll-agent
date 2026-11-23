package io.agentscope.examples.payroll.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelCredentialCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("加密后可解密回原文，nonce 每次随机")
    void roundTripUsesFreshNonce() {
        ModelCredentialCipher cipher = ModelCredentialCipher.of(KEY);

        ModelCredentialCipher.SealedCredential first = cipher.encrypt("dash", "sk-secret");
        ModelCredentialCipher.SealedCredential second = cipher.encrypt("dash", "sk-secret");

        assertEquals("sk-secret", cipher.decrypt("dash", first.ciphertext(), first.nonce()));
        assertNotEquals(first.nonce(), second.nonce());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertEquals(1, first.keyVersion());
        assertFalse(first.ciphertext().contains("sk-secret"));
    }

    @Test
    @DisplayName("AAD 绑定连接 ID，密文不能跨连接搬运")
    void ciphertextBoundToConnectionId() {
        ModelCredentialCipher cipher = ModelCredentialCipher.of(KEY);
        ModelCredentialCipher.SealedCredential sealed = cipher.encrypt("dash", "sk-secret");

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> cipher.decrypt("other", sealed.ciphertext(), sealed.nonce()));
        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("密文被篡改时解密失败")
    void tamperedCiphertextRejected() {
        ModelCredentialCipher cipher = ModelCredentialCipher.of(KEY);
        ModelCredentialCipher.SealedCredential sealed = cipher.encrypt("dash", "sk-secret");
        byte[] raw = Base64.getDecoder().decode(sealed.ciphertext());
        raw[0] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> cipher.decrypt("dash", tampered, sealed.nonce()));
        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("主密钥不匹配时无法解密")
    void decryptWithWrongKeyFails() {
        ModelCredentialCipher sealedBy =
                ModelCredentialCipher.of(KEY);
        ModelCredentialCipher other =
                ModelCredentialCipher.of("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
        ModelCredentialCipher.SealedCredential sealed = sealedBy.encrypt("dash", "sk-secret");

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> other.decrypt("dash", sealed.ciphertext(), sealed.nonce()));
        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("空密钥与非法主密钥长度被拒绝")
    void rejectsBlankAndBadKeySizes() {
        ModelCredentialCipher cipher = ModelCredentialCipher.of(KEY);

        ModelConfigException blank =
                assertThrows(
                        ModelConfigException.class, () -> cipher.encrypt("dash", "  "));
        assertEquals(ModelConfigException.INVALID_CONNECTION, blank.code());

        assertThrows(IllegalArgumentException.class, () -> ModelCredentialCipher.of(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> ModelCredentialCipher.of(null));
    }

    @Test
    @DisplayName("未配置 PAYROLL_SECRET_KEY 时 fail-closed")
    void unavailableCipherFailsClosed() {
        ModelCredentialCipher cipher = ModelCredentialCipher.fromEnvironment();

        if (cipher.available()) {
            String sealed =
                    cipher.encrypt("dash", "sk-env").ciphertext();
            assertFalse(sealed.isBlank());
            assertTrue(cipher.unavailableReason().isEmpty());
            return;
        }

        assertFalse(cipher.unavailableReason().isBlank());
        ModelConfigException encryptError =
                assertThrows(
                        ModelConfigException.class, () -> cipher.encrypt("dash", "sk-any"));
        assertEquals(ModelConfigException.SECRET_KEY_MISSING, encryptError.code());

        ModelConfigException decryptError =
                assertThrows(
                        ModelConfigException.class, () -> cipher.decrypt("dash", "abc", "def"));
        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, decryptError.code());
    }
}
