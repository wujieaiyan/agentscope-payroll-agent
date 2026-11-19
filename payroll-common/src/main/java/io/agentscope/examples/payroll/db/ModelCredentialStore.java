package io.agentscope.examples.payroll.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 页面录入密钥的密文存储；连接元数据仍留在 Nacos。 */
public final class ModelCredentialStore {

    public record Credential(
            String connectionId,
            String ciphertext,
            String nonce,
            int keyVersion,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime lastUsedAt) {}

    private final PayrollDatabase database;

    public ModelCredentialStore(PayrollDatabase database) {
        this.database = database;
    }

    public Optional<Credential> find(String connectionId) {
        String sql =
                """
                SELECT connection_id, cipher_text, nonce, key_version, created_by,
                       created_at, updated_at, last_used_at
                FROM model_credential WHERE connection_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, connectionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new Credential(
                                result.getString("connection_id"),
                                result.getString("cipher_text"),
                                result.getString("nonce"),
                                result.getInt("key_version"),
                                result.getString("created_by"),
                                toLocalDateTime(result.getObject("created_at")),
                                toLocalDateTime(result.getObject("updated_at")),
                                toLocalDateTime(result.getObject("last_used_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取模型密钥失败：" + e.getMessage(), e);
        }
    }

    /** 幂等写入：同连接已有记录时替换密文，保留 createdAt。 */
    public Credential upsert(
            String connectionId,
            String ciphertext,
            String nonce,
            int keyVersion,
            String operator) {
        String update =
                """
                UPDATE model_credential
                SET cipher_text = ?, nonce = ?, key_version = ?, updated_at = ?
                WHERE connection_id = ?
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, ciphertext);
                statement.setString(2, nonce);
                statement.setInt(3, keyVersion);
                statement.setObject(4, now);
                statement.setString(5, connectionId);
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                String insert =
                        """
                        INSERT INTO model_credential
                            (credential_id, connection_id, cipher_text, nonce, key_version,
                             created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, id(connectionId));
                    statement.setString(2, connectionId);
                    statement.setString(3, ciphertext);
                    statement.setString(4, nonce);
                    statement.setInt(5, keyVersion);
                    statement.setString(6, operator);
                    statement.setObject(7, now);
                    statement.setObject(8, now);
                    statement.executeUpdate();
                } catch (SQLIntegrityConstraintViolationException e) {
                    try (PreparedStatement statement = connection.prepareStatement(update)) {
                        statement.setString(1, ciphertext);
                        statement.setString(2, nonce);
                        statement.setInt(3, keyVersion);
                        statement.setObject(4, now);
                        statement.setString(5, connectionId);
                        statement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存模型密钥失败：" + e.getMessage(), e);
        }
        return find(connectionId).orElseThrow();
    }

    public boolean delete(String connectionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM model_credential WHERE connection_id = ?")) {
            statement.setString(1, connectionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除模型密钥失败：" + e.getMessage(), e);
        }
    }

    public void touchUsed(String connectionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE model_credential SET last_used_at = ? WHERE connection_id = ?")) {
            statement.setObject(1, LocalDateTime.now());
            statement.setString(2, connectionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新模型密钥使用时间失败：" + e.getMessage(), e);
        }
    }

    public List<String> listConnectionIds() {
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT connection_id FROM model_credential")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取模型密钥列表失败：" + e.getMessage(), e);
        }
        return ids;
    }

    private static String id(String connectionId) {
        String value = "cred-" + connectionId;
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
