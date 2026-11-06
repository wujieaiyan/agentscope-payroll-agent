package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 对话窗口与消息的 H2 持久化；所有查询都带 user_id，保证跨用户隔离。 */
public final class H2ChatStore {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_ERROR = "ERROR";

    private static final String SESSION_SELECT =
            """
            SELECT s.session_id, s.user_id, s.title, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM chat_messages m WHERE m.session_id = s.session_id)
                       AS message_count,
                   (SELECT m.content FROM chat_messages m
                    WHERE m.session_id = s.session_id
                    ORDER BY m.message_id DESC LIMIT 1) AS last_message
            FROM chat_sessions s
            """;

    private final PayrollDatabase database;

    public H2ChatStore(PayrollDatabase database) {
        this.database = database;
    }

    public ChatSession create(long userId, String title) {
        String sql =
                """
                INSERT INTO chat_sessions (user_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """;
        long id;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setObject(3, now);
            statement.setObject(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建会话失败：" + e.getMessage(), e);
        }
        return find(id).orElseThrow();
    }

    public List<ChatSession> sessions(long userId) {
        String sql =
                SESSION_SELECT
                        + " WHERE s.user_id = ? ORDER BY s.updated_at DESC, s.session_id DESC";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatSession> sessions = new ArrayList<>();
                while (rs.next()) {
                    sessions.add(mapSession(rs));
                }
                return List.copyOf(sessions);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话失败：" + e.getMessage(), e);
        }
    }

    public Optional<ChatSession> find(long sessionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SESSION_SELECT + " WHERE s.session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话失败：" + e.getMessage(), e);
        }
    }

    public ChatSession requireOwned(long sessionId, long userId) {
        ChatSession session =
                find(sessionId).orElseThrow(() -> new PayrollException("会话不存在或无权访问"));
        if (session.userId() != userId) {
            throw new PayrollException("会话不存在或无权访问");
        }
        return session;
    }

    public void rename(long sessionId, String title) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_sessions SET title = ?, updated_at = ? WHERE session_id = ?")) {
            statement.setString(1, title);
            statement.setObject(2, LocalDateTime.now());
            statement.setLong(3, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("重命名会话失败：" + e.getMessage(), e);
        }
    }

    public void touch(long sessionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_sessions SET updated_at = ? WHERE session_id = ?")) {
            statement.setObject(1, LocalDateTime.now());
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新会话时间失败：" + e.getMessage(), e);
        }
    }

    public void delete(long sessionId) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM chat_messages WHERE session_id = ?")) {
                    statement.setLong(1, sessionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM chat_sessions WHERE session_id = ?")) {
                    statement.setLong(1, sessionId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除会话失败：" + e.getMessage(), e);
        }
    }

    public List<ChatMessage> messages(long sessionId) {
        String sql =
                """
                SELECT message_id, session_id, role, content, tools_json, model, created_at
                FROM chat_messages WHERE session_id = ? ORDER BY message_id
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(mapMessage(rs));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话消息失败：" + e.getMessage(), e);
        }
    }

    public ChatMessage append(
            long sessionId, String role, String content, List<String> tools, String model) {
        String sql =
                """
                INSERT INTO chat_messages (session_id, role, content, tools_json, model, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        long id;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sessionId);
            statement.setString(2, role);
            statement.setString(3, content == null ? "" : content);
            if (tools == null || tools.isEmpty()) {
                statement.setNull(4, java.sql.Types.CLOB);
            } else {
                statement.setString(4, JsonSupport.toJson(tools));
            }
            statement.setString(5, model);
            statement.setObject(6, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话消息失败：" + e.getMessage(), e);
        }
        touch(sessionId);
        return new ChatMessage(id, sessionId, role, content, tools, model, LocalDateTime.now());
    }

    public void clear(long sessionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM chat_messages WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清空会话消息失败：" + e.getMessage(), e);
        }
    }

    private static ChatSession mapSession(ResultSet rs) throws SQLException {
        return new ChatSession(
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getString("title"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("message_count"),
                rs.getString("last_message"));
    }

    private static ChatMessage mapMessage(ResultSet rs) throws SQLException {
        String toolsJson = rs.getString("tools_json");
        List<String> tools =
                toolsJson == null || toolsJson.isBlank()
                        ? List.of()
                        : Arrays.asList(JsonSupport.fromJson(toolsJson, String[].class));
        return new ChatMessage(
                rs.getLong("message_id"),
                rs.getLong("session_id"),
                rs.getString("role"),
                rs.getString("content"),
                tools,
                rs.getString("model"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
