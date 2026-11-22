package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.ModelCredentialStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelCredentialServiceTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private PayrollDatabase database;
    private ModelCredentialStore store;
    private ModelCredentialCipher cipher;
    private ModelCredentialService service;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("cred-" + UUID.randomUUID());
        store = new ModelCredentialStore(database);
        cipher = ModelCredentialCipher.of(KEY);
        service = new ModelCredentialService(store, cipher);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("页面密钥加密入库后可按连接解密，且记录最后使用时间")
    void storedCredentialResolves() {
        String reference = service.save("dash", "sk-page-secret", "admin");

        assertEquals("cred-dash", reference);
        String plaintext =
                service.resolveApiKey(connection("dash", reference, ""));
        assertEquals("sk-page-secret", plaintext);

        ModelCredentialStore.Credential stored = store.find("dash").orElseThrow();
        assertFalse(stored.ciphertext().contains("sk-page-secret"));
        assertNotNull(stored.lastUsedAt());
        assertTrue(service.metadata("dash").orElseThrow().stored());
    }

    @Test
    @DisplayName("绑定了凭证引用但库里缺记录时 fail-closed")
    void missingStoredRecordFails() {
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> service.resolveApiKey(connection("dash", "cred-dash", "")));

        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("密文被篡改或灌入非法内容时 fail-closed")
    void tamperedStoredRecordFails() {
        service.save("dash", "sk-page-secret", "admin");
        ModelCredentialCipher.SealedCredential sealed = cipher.encrypt("dash", "sk-page-secret");
        store.upsert("dash", "ZGF0YQ==", sealed.nonce(), 1, "admin");

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> service.resolveApiKey(connection("dash", "cred-dash", "")));

        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("环境变量来源缺失时抛出缺少密钥，不回落到已存页面密钥")
    void missingEnvironmentVariableFails() {
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () ->
                                service.resolveApiKey(
                                        connection("dash", "", "PAYROLL_TEST_ABSENT_KEY_42")));

        assertEquals(ModelConfigException.CREDENTIAL_MISSING, error.code());
        assertTrue(error.getMessage().contains("PAYROLL_TEST_ABSENT_KEY_42"), error.getMessage());
    }

    @Test
    @DisplayName("非 Ollama 连接未配置任何密钥时拒绝调用")
    void unconfiguredCredentialFails() {
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> service.resolveApiKey(connection("dash", "", "")));

        assertEquals(ModelConfigException.CREDENTIAL_MISSING, error.code());
    }

    @Test
    @DisplayName("Ollama 连接不需要密钥")
    void ollamaNeedsNoCredential() {
        ModelConnection ollama =
                new ModelConnection(
                        "local",
                        "本机",
                        ModelConnection.PROVIDER_OLLAMA,
                        "http://localhost:11434",
                        "qwen2.5:3b-instruct",
                        "",
                        "",
                        8192,
                        1024,
                        0.1,
                        120,
                        List.of(),
                        true,
                        null,
                        "",
                        ModelConnection.ORIGIN_STORED);

        assertEquals("", service.resolveApiKey(ollama));
    }

    @Test
    @DisplayName("清理孤儿凭证只删除连接已不存在的记录")
    void cleanupRemovesOnlyOrphans() {
        service.save("keep", "sk-keep", "admin");
        service.save("orphan", "sk-orphan", "admin");

        int removed = service.cleanupOrphans(Set.of("keep"));

        assertEquals(1, removed);
        assertTrue(store.find("keep").isPresent());
        assertTrue(store.find("orphan").isEmpty());
    }

    @Test
    @DisplayName("清除密钥后连接不再可用")
    void clearCredentialMakesConnectionUnusable() {
        String reference = service.save("dash", "sk-page-secret", "admin");
        service.clear("dash");

        assertFalse(service.metadata("dash").isPresent());
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> service.resolveApiKey(connection("dash", reference, "")));
        assertEquals(ModelConfigException.CREDENTIAL_UNREADABLE, error.code());
    }

    @Test
    @DisplayName("主密钥缺失时拒绝保存页面密钥")
    void unavailableSecretKeyRejectsSave() {
        ModelCredentialCipher envCipher = ModelCredentialCipher.fromEnvironment();
        if (envCipher.available()) {
            assertEquals(true, new ModelCredentialService(store, envCipher).secretKeyAvailable());
            return;
        }
        ModelCredentialService locked = new ModelCredentialService(store, envCipher);

        assertFalse(locked.secretKeyAvailable());
        assertFalse(locked.secretKeyUnavailableReason().isBlank());
        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> locked.save("dash", "sk-page-secret", "admin"));
        assertEquals(ModelConfigException.SECRET_KEY_MISSING, error.code());
    }

    @Test
    @DisplayName("环境变量凭证的版本是变量名，不暴露密钥")
    void envCredentialVersionIsEnvName() {
        assertEquals(
                "env:PAYROLL_TEST_KEY",
                service.versionOf(connection("dash", "", "PAYROLL_TEST_KEY")));
        assertEquals("", service.versionOf(null));
    }

    @Test
    @DisplayName("页面凭证版本取更新时间，5s 内命中缓存，保存后立即失效")
    void storedCredentialVersionTracksUpdatedAt() {
        service.save("dash", "sk-page-secret", "admin");
        ModelConnection dash = connection("dash", "cred-dash", "");
        setUpdatedAt("dash", LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        String cached = String.valueOf(epochSeconds(LocalDateTime.of(2020, 1, 2, 3, 4, 5)));

        assertEquals(cached, service.versionOf(dash));

        setUpdatedAt("dash", LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        assertEquals(cached, service.versionOf(dash), "5s 内应命中版本缓存");

        service.save("dash", "sk-page-secret-2", "admin");
        assertNotEquals(cached, service.versionOf(dash), "保存后应失效并返回新更新时间");
    }

    @Test
    @DisplayName("清除凭证后版本变为 missing 且缓存失效")
    void clearInvalidatesCachedVersion() {
        service.save("dash", "sk-page-secret", "admin");
        ModelConnection dash = connection("dash", "cred-dash", "");
        setUpdatedAt("dash", LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        assertEquals(
                String.valueOf(epochSeconds(LocalDateTime.of(2020, 1, 2, 3, 4, 5))),
                service.versionOf(dash));

        service.clear("dash");

        assertEquals("missing", service.versionOf(dash));
    }

    private void setUpdatedAt(String connectionId, LocalDateTime updatedAt) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE model_credential SET updated_at = ? WHERE connection_id = ?")) {
            statement.setObject(1, updatedAt);
            statement.setString(2, connectionId);
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException e) {
            throw new IllegalStateException("更新测试凭证时间失败", e);
        }
    }

    private static long epochSeconds(LocalDateTime value) {
        return value.toEpochSecond(ZoneOffset.UTC);
    }

    private static ModelConnection connection(
            String id, String credentialRef, String apiKeyEnv) {
        return new ModelConnection(
                id,
                "连接 " + id,
                ModelConnection.PROVIDER_OPENAI,
                "https://api.test",
                "m",
                apiKeyEnv,
                credentialRef,
                8192,
                1024,
                0.2,
                120,
                List.of(),
                true,
                null,
                "",
                ModelConnection.ORIGIN_STORED);
    }
}
