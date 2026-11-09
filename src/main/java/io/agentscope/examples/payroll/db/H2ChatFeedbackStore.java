package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 助手消息反馈的 H2 仓储；message_id + user_id 唯一，重复提交更新原记录。 */
public final class H2ChatFeedbackStore {

    private static final String COLUMNS =
            "feedback_id, message_id, session_id, user_id, rating, tags_json, comment_text, created_at";

    private final PayrollDatabase database;

    public H2ChatFeedbackStore(PayrollDatabase database) {
        this.database = database;
    }

    public ChatFeedback upsert(
            long messageId,
            long sessionId,
            long userId,
            String rating,
            List<String> tags,
            String comment) {
        String tagsJson =
                tags == null || tags.isEmpty() ? null : JsonSupport.toJson(List.copyOf(tags));
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Long existing = findId(connection, messageId, userId);
                if (existing == null) {
                    insert(connection, messageId, sessionId, userId, rating, tagsJson, comment);
                } else {
                    update(connection, existing, rating, tagsJson, comment);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存消息反馈失败：" + e.getMessage(), e);
        }
        return find(messageId, userId).orElseThrow();
    }

    public Optional<ChatFeedback> find(long messageId, long userId) {
        String sql = "SELECT " + COLUMNS + " FROM chat_feedback WHERE message_id = ? AND user_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息反馈失败：" + e.getMessage(), e);
        }
    }

    public List<ChatFeedback> list(long messageId) {
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM chat_feedback WHERE message_id = ? ORDER BY feedback_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatFeedback> feedback = new ArrayList<>();
                while (rs.next()) {
                    feedback.add(map(rs));
                }
                return List.copyOf(feedback);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息反馈失败：" + e.getMessage(), e);
        }
    }

    /** 统计窗口内的全部反馈，新的在前。 */
    public List<ChatFeedback> listSince(LocalDateTime since) {
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM chat_feedback WHERE created_at >= ? ORDER BY feedback_id DESC";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, since);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatFeedback> feedback = new ArrayList<>();
                while (rs.next()) {
                    feedback.add(map(rs));
                }
                return List.copyOf(feedback);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询统计窗口反馈失败：" + e.getMessage(), e);
        }
    }

    public int deleteBySession(long sessionId) {
        String sql = "DELETE FROM chat_feedback WHERE session_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除会话反馈失败：" + e.getMessage(), e);
        }
    }

    private static Long findId(Connection connection, long messageId, long userId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT feedback_id FROM chat_feedback WHERE message_id = ? AND user_id = ?")) {
            statement.setLong(1, messageId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static void insert(
            Connection connection,
            long messageId,
            long sessionId,
            long userId,
            String rating,
            String tagsJson,
            String comment)
            throws SQLException {
        String sql =
                """
                INSERT INTO chat_feedback (message_id, session_id, user_id, rating,
                    tags_json, comment_text, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, messageId);
            statement.setLong(2, sessionId);
            statement.setLong(3, userId);
            statement.setString(4, rating);
            statement.setString(5, tagsJson);
            statement.setString(6, comment);
            statement.setObject(7, LocalDateTime.now());
            statement.executeUpdate();
        }
    }

    private static void update(
            Connection connection,
            long feedbackId,
            String rating,
            String tagsJson,
            String comment)
            throws SQLException {
        String sql =
                """
                UPDATE chat_feedback
                SET rating = ?, tags_json = ?, comment_text = ?
                WHERE feedback_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rating);
            statement.setString(2, tagsJson);
            statement.setString(3, comment);
            statement.setLong(4, feedbackId);
            statement.executeUpdate();
        }
    }

    private static ChatFeedback map(ResultSet rs) throws SQLException {
        String tagsJson = rs.getString("tags_json");
        List<String> tags =
                tagsJson == null || tagsJson.isBlank()
                        ? List.of()
                        : JsonSupport.fromJson(tagsJson, new TypeReference<List<String>>() {});
        return new ChatFeedback(
                rs.getLong("feedback_id"),
                rs.getLong("message_id"),
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getString("rating"),
                tags,
                rs.getString("comment_text"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
