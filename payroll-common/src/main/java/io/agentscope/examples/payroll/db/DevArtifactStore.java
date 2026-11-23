package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.devteam.DevArtifact;
import io.agentscope.examples.payroll.devteam.DevArtifactType;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 研发域产物库（P0-5）：按 {@code (task_id, artifact_type)} 递增版本，数据库是唯一事实源。
 *
 * <p>同内容重复提交直接返回最新版本，避免任务重投产生噪声版本；补丁类产物必须带
 * diff hash（未显式提供时取内容哈希）。产物超过上限直接拒绝，不做静默截断。
 */
public final class DevArtifactStore {

    public static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TASK_ID = 64;
    private static final int MAX_OWNER = 64;
    private static final int MAX_TITLE = 200;
    private static final int MAX_DIFF_HASH = 128;
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final String SELECT =
            """
            SELECT artifact_id, task_id, artifact_type, version, owner, title, content,
                   content_hash, diff_hash, size_bytes, created_at
            FROM dev_artifacts
            """;

    private final PayrollDatabase database;

    public DevArtifactStore(PayrollDatabase database) {
        this.database = database;
    }

    public DevArtifact save(
            String taskId,
            DevArtifactType type,
            String owner,
            String title,
            String content,
            String diffHash) {
        String task = requireTaskId(taskId);
        if (type == null) {
            throw new PayrollException("产物类型不能为空");
        }
        String ownerValue = requireOwner(owner);
        String contentValue = content == null ? "" : content;
        long sizeBytes = contentValue.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > MAX_CONTENT_BYTES) {
            throw new PayrollException(
                    "产物超过 " + (MAX_CONTENT_BYTES / (1024 * 1024)) + "MB 上限：" + sizeBytes + " 字节");
        }
        String contentHash = sha256(contentValue);
        String titleValue = normalizeTitle(title, type);
        String diff = diffHash == null || diffHash.isBlank() ? contentHash : trimTo(diffHash, MAX_DIFF_HASH);
        String diffValue = type.patch() ? diff : null;

        Optional<DevArtifact> latest = latest(task, type);
        if (latest.isPresent()
                && latest.get().contentHash().equals(contentHash)
                && latest.get().title().equals(titleValue)
                && java.util.Objects.equals(latest.get().diffHash(), diffValue)) {
            return latest.get();
        }

        int version;
        long artifactId;
        LocalDateTime now = LocalDateTime.now();
        String sql =
                """
                INSERT INTO dev_artifacts
                    (task_id, artifact_type, version, owner, title, content, content_hash,
                     diff_hash, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                version = nextVersion(connection, task, type.id());
                try (PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, task);
                    statement.setString(2, type.id());
                    statement.setInt(3, version);
                    statement.setString(4, ownerValue);
                    statement.setString(5, titleValue);
                    statement.setString(6, contentValue);
                    statement.setString(7, contentHash);
                    statement.setString(8, diffValue);
                    statement.setLong(9, sizeBytes);
                    statement.setObject(10, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        artifactId = keys.getLong(1);
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存研发产物失败：" + e.getMessage(), e);
        }
        return find(task, type, version)
                .orElseGet(
                        () ->
                                new DevArtifact(
                                        artifactId,
                                        task,
                                        type.id(),
                                        version,
                                        ownerValue,
                                        titleValue,
                                        contentValue,
                                        contentHash,
                                        diffValue,
                                        sizeBytes,
                                        now));
    }

    /** 任务的全部产物，按写入顺序返回。 */
    public List<DevArtifact> list(String taskId) {
        String task = requireTaskId(taskId);
        String sql = SELECT + " WHERE task_id = ? ORDER BY created_at, artifact_type, version";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task);
            try (ResultSet rs = statement.executeQuery()) {
                List<DevArtifact> artifacts = new ArrayList<>();
                while (rs.next()) {
                    artifacts.add(map(rs));
                }
                return List.copyOf(artifacts);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询研发产物失败：" + e.getMessage(), e);
        }
    }

    public List<DevArtifact> history(String taskId, DevArtifactType type) {
        String task = requireTaskId(taskId);
        requireType(type);
        String sql =
                SELECT
                        + " WHERE task_id = ? AND artifact_type = ? ORDER BY version";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task);
            statement.setString(2, type.id());
            try (ResultSet rs = statement.executeQuery()) {
                List<DevArtifact> artifacts = new ArrayList<>();
                while (rs.next()) {
                    artifacts.add(map(rs));
                }
                return List.copyOf(artifacts);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询研发产物版本失败：" + e.getMessage(), e);
        }
    }

    public Optional<DevArtifact> latest(String taskId, DevArtifactType type) {
        String task = requireTaskId(taskId);
        requireType(type);
        String sql =
                SELECT
                        + " WHERE task_id = ? AND artifact_type = ? ORDER BY version DESC LIMIT 1";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task);
            statement.setString(2, type.id());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询最新研发产物失败：" + e.getMessage(), e);
        }
    }

    public Optional<DevArtifact> find(String taskId, DevArtifactType type, int version) {
        String task = requireTaskId(taskId);
        requireType(type);
        String sql = SELECT + " WHERE task_id = ? AND artifact_type = ? AND version = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task);
            statement.setString(2, type.id());
            statement.setInt(3, version);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询研发产物失败：" + e.getMessage(), e);
        }
    }

    private static int nextVersion(Connection connection, String taskId, String typeId)
            throws SQLException {
        String sql =
                """
                SELECT COALESCE(MAX(version), 0)
                FROM dev_artifacts
                WHERE task_id = ? AND artifact_type = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setString(2, typeId);
            try (ResultSet rs = statement.executeQuery()) {
                return (rs.next() ? rs.getInt(1) : 0) + 1;
            }
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String requireTaskId(String taskId) {
        String value = taskId == null ? "" : taskId.trim();
        if (!TASK_ID.matcher(value).matches() || value.length() > MAX_TASK_ID) {
            throw new PayrollException("任务 ID 非法：仅允许字母、数字、点、下划线与短横线，最长 64 位");
        }
        return value;
    }

    private static String requireOwner(String owner) {
        String value = owner == null ? "" : owner.trim();
        if (value.isEmpty()) {
            throw new PayrollException("产物 owner 不能为空");
        }
        return trimTo(value, MAX_OWNER);
    }

    private static void requireType(DevArtifactType type) {
        if (type == null) {
            throw new PayrollException("产物类型不能为空");
        }
    }

    private static String normalizeTitle(String title, DevArtifactType type) {
        String value = title == null ? "" : title.trim();
        return value.isEmpty() ? type.fileName() : trimTo(value, MAX_TITLE);
    }

    private static String trimTo(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static DevArtifact map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new DevArtifact(
                rs.getLong("artifact_id"),
                rs.getString("task_id"),
                rs.getString("artifact_type"),
                rs.getInt("version"),
                rs.getString("owner"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("content_hash"),
                rs.getString("diff_hash"),
                rs.getLong("size_bytes"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }
}
