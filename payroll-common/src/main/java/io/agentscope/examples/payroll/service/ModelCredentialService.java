package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.ModelCredentialStore;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelCredentialCipher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 密钥解析：环境变量名或数据库密文二选一；解密失败一律 fail-closed，
 * 不回落另一来源，明文密钥不写入任何缓存字段。
 */
public final class ModelCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialService.class);

    public record Metadata(boolean stored, LocalDateTime createdAt, LocalDateTime lastUsedAt) {}

    /** 凭证版本缓存：只存时间戳或环境变量名，绝不缓存密钥本身。 */
    private record CachedVersion(String value, long expiresAt) {}

    private static final long VERSION_CACHE_MILLIS = 5_000L;

    private final ModelCredentialStore store;
    private final ModelCredentialCipher cipher;
    private final Map<String, CachedVersion> versionCache = new HashMap<>();

    public ModelCredentialService(ModelCredentialStore store, ModelCredentialCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    public boolean secretKeyAvailable() {
        return cipher.available();
    }

    public String secretKeyUnavailableReason() {
        return cipher.unavailableReason();
    }

    /** 加密并保存页面录入的密钥，返回凭证引用。 */
    public String save(String connectionId, String plaintext, String operator) {
        ModelCredentialCipher.SealedCredential sealed = cipher.encrypt(connectionId, plaintext);
        store.upsert(
                connectionId,
                sealed.ciphertext(),
                sealed.nonce(),
                sealed.keyVersion(),
                operator);
        invalidateVersion(connectionId);
        log.info("[Model] 已保存连接 {} 的页面密钥（操作人 {}）", connectionId, operator);
        return "cred-" + connectionId;
    }

    public void clear(String connectionId) {
        store.delete(connectionId);
        invalidateVersion(connectionId);
        log.info("[Model] 已清除连接 {} 的页面密钥", connectionId);
    }

    /**
     * 凭证版本：环境变量模式返回变量名，页面密钥返回 updated_at 秒级时间戳。
     *
     * <p>只用于模型实例缓存键，不暴露密钥；结果缓存 5s，保存/清除时主动失效。
     */
    public String versionOf(ModelConnection connection) {
        if (connection == null) {
            return "";
        }
        if (connection.credentialRef() == null || connection.credentialRef().isBlank()) {
            return connection.apiKeyEnv() == null ? "" : "env:" + connection.apiKeyEnv();
        }
        long now = System.currentTimeMillis();
        synchronized (versionCache) {
            CachedVersion cached = versionCache.get(connection.id());
            if (cached != null && cached.expiresAt() > now) {
                return cached.value();
            }
        }
        // 凭证表主键是 connection.id()，不是展示用的 credentialRef。
        String value =
                store.find(connection.id())
                        .map(
                                credential ->
                                        credential.updatedAt() == null
                                                ? "missing"
                                                : String.valueOf(
                                                        credential
                                                                .updatedAt()
                                                                .toEpochSecond(ZoneOffset.UTC)))
                        .orElse("missing");
        synchronized (versionCache) {
            versionCache.put(connection.id(), new CachedVersion(value, now + VERSION_CACHE_MILLIS));
        }
        return value;
    }

    private void invalidateVersion(String connectionId) {
        synchronized (versionCache) {
            versionCache.remove(connectionId);
        }
    }

    /** 取连接可用的 API Key；Ollama 返回空串。 */
    public String resolveApiKey(ModelConnection connection) {
        if (!connection.requiresApiKey()) {
            return "";
        }
        if (connection.credentialRef() != null && !connection.credentialRef().isBlank()) {
            return decryptStored(connection);
        }
        if (connection.apiKeyEnv() != null && !connection.apiKeyEnv().isBlank()) {
            String value = System.getenv(connection.apiKeyEnv());
            if (value == null || value.isBlank()) {
                throw new ModelConfigException(
                        ModelConfigException.CREDENTIAL_MISSING,
                        "环境变量 " + connection.apiKeyEnv() + " 未设置");
            }
            return value.trim();
        }
        throw new ModelConfigException(
                ModelConfigException.CREDENTIAL_MISSING, "连接「" + connection.name() + "」未配置密钥");
    }

    public Optional<Metadata> metadata(String connectionId) {
        return store.find(connectionId)
                .map(
                        credential ->
                                new Metadata(
                                        true,
                                        credential.createdAt(),
                                        credential.lastUsedAt()));
    }

    /** 启动时清理「凭证存在但连接不存在」的孤儿记录。 */
    public int cleanupOrphans(Set<String> validConnectionIds) {
        int removed = 0;
        for (String connectionId : store.listConnectionIds()) {
            if (!validConnectionIds.contains(connectionId)) {
                store.delete(connectionId);
                removed++;
                log.warn("[Model] 清理孤儿模型凭证：{}", connectionId);
            }
        }
        return removed;
    }

    private String decryptStored(ModelConnection connection) {
        ModelCredentialStore.Credential credential =
                store.find(connection.id())
                        .orElseThrow(
                                () ->
                                        new ModelConfigException(
                                                ModelConfigException.CREDENTIAL_UNREADABLE,
                                                "连接「"
                                                        + connection.name()
                                                        + "」的已存密钥记录缺失，请重新录入"));
        String plaintext =
                cipher.decrypt(
                        connection.id(), credential.ciphertext(), credential.nonce());
        try {
            store.touchUsed(connection.id());
        } catch (RuntimeException e) {
            log.warn("[Model] 更新密钥最后使用时间失败：{}", e.getMessage());
        }
        return plaintext;
    }
}
