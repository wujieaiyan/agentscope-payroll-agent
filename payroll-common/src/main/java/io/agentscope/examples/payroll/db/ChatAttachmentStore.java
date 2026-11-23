package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.ChatAttachment;
import io.agentscope.examples.payroll.domain.ChatAttachmentRef;
import io.agentscope.examples.payroll.domain.ExtractionMeta;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 对话附件元数据；原文件与提取文本由 Web 层的附件服务管理。 */
public final class ChatAttachmentStore {

    private static final String SELECT =
            """
            SELECT attachment_id, session_id, user_id, message_id, name, ext, size_bytes,
                   storage_key, text_chars, status, error, progress, extraction_meta, created_at
            FROM chat_attachments
            """;

    private final PayrollDatabase database;

    public ChatAttachmentStore(PayrollDatabase database) {
        this.database = database;
    }

    public ChatAttachment create(
            long sessionId,
            long userId,
            String name,
            String ext,
            long sizeBytes,
            String storageKey) {
        String sql =
                """
                INSERT INTO chat_attachments
                    (session_id, user_id, message_id, name, ext, size_bytes, storage_key,
                     text_chars, status, error, created_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, 0, ?, NULL, ?)
                """;
        long id;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, userId);
            statement.setString(3, name);
            statement.setString(4, ext);
            statement.setLong(5, sizeBytes);
            statement.setString(6, storageKey);
            statement.setString(7, ChatAttachment.STATUS_PARSING);
            statement.setObject(8, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("登记附件失败：" + e.getMessage(), e);
        }
        return find(sessionId, id).orElseThrow();
    }

    public void markReady(
            long sessionId, long attachmentId, int textChars, ExtractionMeta meta) {
        String sql =
                """
                UPDATE chat_attachments
                SET status = ?, text_chars = ?, error = NULL, progress = 100, extraction_meta = ?
                WHERE session_id = ? AND attachment_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChatAttachment.STATUS_READY);
            statement.setInt(2, textChars);
            statement.setString(3, meta == null ? null : JsonSupport.toJson(meta));
            statement.setLong(4, sessionId);
            statement.setLong(5, attachmentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新附件状态失败：" + e.getMessage(), e);
        }
    }

    /** 重新识别：清空上次结果并回到异步中间态。 */
    public void markParsing(long sessionId, long attachmentId) {
        String sql =
                """
                UPDATE chat_attachments
                SET status = ?, text_chars = 0, error = NULL, progress = 0, extraction_meta = NULL
                WHERE session_id = ? AND attachment_id = ? AND message_id IS NULL
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChatAttachment.STATUS_PARSING);
            statement.setLong(2, sessionId);
            statement.setLong(3, attachmentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("重置附件状态失败：" + e.getMessage(), e);
        }
    }

    public void updateProgress(long sessionId, long attachmentId, int progress) {
        int bounded = Math.max(0, Math.min(100, progress));
        String sql =
                """
                UPDATE chat_attachments
                SET progress = ?
                WHERE session_id = ? AND attachment_id = ? AND status = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, bounded);
            statement.setLong(2, sessionId);
            statement.setLong(3, attachmentId);
            statement.setString(4, ChatAttachment.STATUS_PARSING);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新附件进度失败：" + e.getMessage(), e);
        }
    }

    public void markFailed(long sessionId, long attachmentId, String error) {
        markFailed(sessionId, attachmentId, error, null);
    }

    public void markFailed(
            long sessionId, long attachmentId, String error, ExtractionMeta meta) {
        String message = error == null ? "解析失败" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        String sql =
                """
                UPDATE chat_attachments
                SET status = ?, error = ?, extraction_meta = ?
                WHERE session_id = ? AND attachment_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChatAttachment.STATUS_FAILED);
            statement.setString(2, message);
            statement.setString(3, meta == null ? null : JsonSupport.toJson(meta));
            statement.setLong(4, sessionId);
            statement.setLong(5, attachmentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新附件状态失败：" + e.getMessage(), e);
        }
    }

    public Optional<ChatAttachment> find(long sessionId, long attachmentId) {
        String sql = SELECT + " WHERE session_id = ? AND attachment_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, attachmentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询附件失败：" + e.getMessage(), e);
        }
    }

    /** 启动恢复与运维排查：按状态跨会话取附件。 */
    public List<ChatAttachment> findByStatus(String status, int limit) {
        String sql = SELECT + " WHERE status = ? ORDER BY attachment_id LIMIT ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatAttachment> attachments = new ArrayList<>();
                while (rs.next()) {
                    attachments.add(map(rs));
                }
                return List.copyOf(attachments);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("按状态查询附件失败：" + e.getMessage(), e);
        }
    }

    /** 会话内尚未发送的附件，按上传顺序返回。 */
    public List<ChatAttachment> pending(long sessionId, long userId) {
        String sql =
                SELECT
                        + """
                         WHERE session_id = ? AND user_id = ? AND message_id IS NULL
                         ORDER BY attachment_id
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatAttachment> attachments = new ArrayList<>();
                while (rs.next()) {
                    attachments.add(map(rs));
                }
                return List.copyOf(attachments);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询待发送附件失败：" + e.getMessage(), e);
        }
    }

    public int countPending(long sessionId) {
        String sql =
                "SELECT COUNT(*) FROM chat_attachments WHERE session_id = ? AND message_id IS NULL";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计待发送附件失败：" + e.getMessage(), e);
        }
    }

    /** 把待发送附件绑定到用户消息；只绑定仍未被占用的 READY 附件。 */
    public int bind(long sessionId, long userId, List<Long> attachmentIds, long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return 0;
        }
        String sql =
                """
                UPDATE chat_attachments
                SET message_id = ?
                WHERE session_id = ? AND user_id = ? AND attachment_id = ?
                  AND message_id IS NULL AND status = ?
                """;
        int bound = 0;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (long attachmentId : attachmentIds) {
                    statement.setLong(1, messageId);
                    statement.setLong(2, sessionId);
                    statement.setLong(3, userId);
                    statement.setLong(4, attachmentId);
                    statement.setString(5, ChatAttachment.STATUS_READY);
                    bound += statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("绑定附件失败：" + e.getMessage(), e);
        }
        return bound;
    }

    public void deletePending(long sessionId, long userId, long attachmentId) {
        String sql =
                """
                DELETE FROM chat_attachments
                WHERE session_id = ? AND user_id = ? AND attachment_id = ? AND message_id IS NULL
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, userId);
            statement.setLong(3, attachmentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除附件失败：" + e.getMessage(), e);
        }
    }

    /** 会话删除/清空时由 H2ChatStore 在同一事务内调用。 */
    static void deleteForSession(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM chat_attachments WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
    }

    /** 消息展示用的附件引用，按消息 ID 分组，避免逐条查询。 */
    public Map<Long, List<ChatAttachmentRef>> refsByMessage(long sessionId) {
        String sql =
                """
                SELECT attachment_id, message_id, name, ext, size_bytes, status, text_chars, error, progress
                FROM chat_attachments
                WHERE session_id = ? AND message_id IS NOT NULL
                ORDER BY attachment_id
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                Map<Long, List<ChatAttachmentRef>> grouped = new HashMap<>();
                while (rs.next()) {
                    grouped.computeIfAbsent(rs.getLong("message_id"), key -> new ArrayList<>())
                            .add(
                                    new ChatAttachmentRef(
                                            rs.getLong("attachment_id"),
                                            rs.getString("name"),
                                            rs.getString("ext"),
                                            rs.getLong("size_bytes"),
                                            rs.getString("status"),
                                            rs.getInt("text_chars"),
                                            rs.getString("error"),
                                            rs.getInt("progress"),
                                            null));
                }
                return grouped;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息附件失败：" + e.getMessage(), e);
        }
    }

    public List<ChatAttachmentRef> refs(long sessionId, long messageId) {
        String sql =
                """
                SELECT attachment_id, name, ext, size_bytes, status, text_chars, error, progress, extraction_meta
                FROM chat_attachments
                WHERE session_id = ? AND message_id = ?
                ORDER BY attachment_id
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatAttachmentRef> refs = new ArrayList<>();
                while (rs.next()) {
                    refs.add(
                            new ChatAttachmentRef(
                                    rs.getLong("attachment_id"),
                                    rs.getString("name"),
                                    rs.getString("ext"),
                                    rs.getLong("size_bytes"),
                                    rs.getString("status"),
                                    rs.getInt("text_chars"),
                                    rs.getString("error"),
                                    rs.getInt("progress"),
                                    readMeta(rs.getString("extraction_meta"))));
                }
                return List.copyOf(refs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息附件失败：" + e.getMessage(), e);
        }
    }

    /** 待发送附件的归属校验；不存在与越权统一返回同一提示。 */
    public ChatAttachment require(long sessionId, long attachmentId) {
        return find(sessionId, attachmentId)
                .orElseThrow(() -> new PayrollException("附件不存在或无权访问"));
    }

    private static ChatAttachment map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ChatAttachment(
                rs.getLong("attachment_id"),
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getLong("message_id"),
                rs.getString("name"),
                rs.getString("ext"),
                rs.getLong("size_bytes"),
                rs.getString("storage_key"),
                rs.getInt("text_chars"),
                rs.getString("status"),
                rs.getString("error"),
                rs.getInt("progress"),
                readMeta(rs.getString("extraction_meta")),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    /** 历史数据或人工改库可能留下非法 JSON，展示链路不应因此整体失败。 */
    private static ExtractionMeta readMeta(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonSupport.fromJson(json, ExtractionMeta.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
