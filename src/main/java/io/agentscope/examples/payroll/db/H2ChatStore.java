package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.repository.ChatContextStore;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 对话窗口与消息的 H2 持久化；所有查询都带 user_id，保证跨用户隔离。 */
public final class H2ChatStore implements ChatContextStore {

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

    /** 全部会话，仅用于管理员重建向量索引。 */
    public List<ChatSession> allSessions() {
        String sql = SESSION_SELECT + " ORDER BY s.user_id, s.session_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<ChatSession> sessions = new ArrayList<>();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
            return List.copyOf(sessions);
        } catch (SQLException e) {
            throw new IllegalStateException("查询全部会话失败：" + e.getMessage(), e);
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
                                "DELETE FROM chat_tool_traces WHERE session_id = ?")) {
                    statement.setLong(1, sessionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM chat_messages WHERE session_id = ?")) {
                    statement.setLong(1, sessionId);
                    statement.executeUpdate();
                }
                deleteTeamData(connection, sessionId);
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

    public Optional<ChatMessage> message(long sessionId, long messageId) {
        String sql =
                """
                SELECT message_id, session_id, role, content, tools_json, model, created_at
                FROM chat_messages WHERE session_id = ? AND message_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapMessage(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话消息失败：" + e.getMessage(), e);
        }
    }

    /** 会话内用户提问次数，用作向量记录的轮次索引。 */
    public int userTurnCount(long sessionId) {
        String sql = "SELECT COUNT(*) FROM chat_messages WHERE session_id = ? AND role = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setString(2, ROLE_USER);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计用户轮次失败：" + e.getMessage(), e);
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

    /**
     * 助手回复与本次回答的工具追溯在同一事务内落库，避免出现“有消息无依据”的半条记录。
     */
    public ChatMessage appendAssistant(
            long sessionId,
            String content,
            List<String> tools,
            String model,
            List<ChatToolTrace> traces) {
        String sql =
                """
                INSERT INTO chat_messages (session_id, role, content, tools_json, model, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        LocalDateTime now = LocalDateTime.now();
        long id;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, sessionId);
                    statement.setString(2, ROLE_ASSISTANT);
                    statement.setString(3, content == null ? "" : content);
                    if (tools == null || tools.isEmpty()) {
                        statement.setNull(4, java.sql.Types.CLOB);
                    } else {
                        statement.setString(4, JsonSupport.toJson(tools));
                    }
                    statement.setString(5, model);
                    statement.setObject(6, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
                if (traces != null) {
                    for (ChatToolTrace trace : traces) {
                        insertTrace(connection, sessionId, id, trace, now);
                    }
                }                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话消息失败：" + e.getMessage(), e);
        }
        touch(sessionId);
        return new ChatMessage(id, sessionId, ROLE_ASSISTANT, content, tools, model, now);
    }

    private static long insertTrace(
            Connection connection,
            long sessionId,
            long messageId,
            ChatToolTrace trace,
            LocalDateTime now)
            throws SQLException {
        String sql =
                """
                INSERT INTO chat_tool_traces (message_id, session_id, tool_call_id, tool_name,
                    agent, run_id, node_id, args_json, result_json, truncated, result_state,
                    duration_ms, employees_json, period, department, amounts_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, messageId);
            statement.setLong(2, sessionId);
            statement.setString(3, trace.toolCallId());
            statement.setString(4, trace.toolName());
            statement.setString(5, trace.agent());
            if (trace.runId() > 0) {
                statement.setLong(6, trace.runId());
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.setString(7, trace.nodeId());
            statement.setString(8, trace.argsJson());
            statement.setString(9, trace.resultJson());
            statement.setBoolean(10, trace.truncated());
            statement.setString(11, trace.resultState());
            statement.setLong(12, trace.durationMs());
            statement.setString(
                    13,
                    trace.employees().isEmpty()
                            ? null
                            : JsonSupport.toJson(trace.employees()));
            statement.setString(14, trace.period());
            statement.setString(15, trace.department());
            statement.setString(
                    16, trace.amounts().isEmpty() ? null : JsonSupport.toJson(trace.amounts()));
            statement.setObject(17, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    /**
     * 影子运行没有助手消息，追溯以 message_id=0 单独落库，通过 run_id 查询；
     * 不写入主消息，避免污染聊天页的计算依据。
     */
    public List<ChatToolTrace> appendTraces(
            long sessionId, long messageId, List<ChatToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<ChatToolTrace> persisted = new ArrayList<>();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                for (ChatToolTrace trace : traces) {
                    long id = insertTrace(connection, sessionId, messageId, trace, now);
                    persisted.add(
                            new ChatToolTrace(
                                    id,
                                    messageId,
                                    sessionId,
                                    trace.toolCallId(),
                                    trace.toolName(),
                                    trace.agent(),
                                    trace.runId(),
                                    trace.nodeId(),
                                    trace.argsJson(),
                                    trace.resultJson(),
                                    trace.truncated(),
                                    trace.resultState(),
                                    trace.durationMs(),
                                    trace.employees(),
                                    trace.period(),
                                    trace.department(),
                                    trace.amounts(),
                                    now));
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存工具追溯失败：" + e.getMessage(), e);
        }
        return List.copyOf(persisted);
    }

    /** 按运行读取 shadow/回退运行的工具追溯；主消息追溯仍走 {@link #traces(long, long)}。 */
    public List<ChatToolTrace> tracesForRun(long runId) {
        String sql =
                """
                SELECT trace_id, message_id, session_id, tool_call_id, tool_name, agent,
                       run_id, node_id, args_json, result_json, truncated, result_state,
                       duration_ms, employees_json, period, department, amounts_json, created_at
                FROM chat_tool_traces
                WHERE run_id = ?
                ORDER BY trace_id
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatToolTrace> traces = new ArrayList<>();
                while (rs.next()) {
                    traces.add(mapTrace(rs));
                }
                return List.copyOf(traces);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询运行工具追溯失败：" + e.getMessage(), e);
        }
    }

    /** 某条助手消息的计算依据；sessionId 参与过滤，防止跨会话越权读取。 */
    public List<ChatToolTrace> traces(long sessionId, long messageId) {
        String sql =
                """
                SELECT trace_id, message_id, session_id, tool_call_id, tool_name, agent,
                       run_id, node_id, args_json, result_json, truncated, result_state,
                       duration_ms, employees_json, period, department, amounts_json, created_at
                FROM chat_tool_traces
                WHERE session_id = ? AND message_id = ?
                ORDER BY trace_id
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatToolTrace> traces = new ArrayList<>();
                while (rs.next()) {
                    traces.add(mapTrace(rs));
                }
                return List.copyOf(traces);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询工具追溯失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String persona(long sessionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT persona_key FROM chat_sessions WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                String value = rs.getString(1);
                return value == null ? "" : value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话角色失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void savePersona(long sessionId, String persona) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_sessions SET persona_key = ?, updated_at = ? WHERE session_id = ?")) {
            statement.setString(1, persona);
            statement.setObject(2, LocalDateTime.now());
            statement.setLong(3, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话角色失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ChatContextSnapshot loadContext(long sessionId) {
        String sql =
                "SELECT context_summary, context_turns FROM chat_sessions WHERE session_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return ChatContextSnapshot.EMPTY;
                }
                String summary = rs.getString(1);
                return new ChatContextSnapshot(summary == null ? "" : summary, rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话上下文失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveContext(long sessionId, ChatContextSnapshot snapshot) {
        String sql =
                """
                UPDATE chat_sessions
                SET context_summary = ?, context_turns = ?, updated_at = ?
                WHERE session_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.summary());
            statement.setInt(2, snapshot.summarizedTurns());
            statement.setObject(3, LocalDateTime.now());
            statement.setLong(4, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话上下文失败：" + e.getMessage(), e);
        }
    }

    public void clear(long sessionId) {
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM chat_tool_traces WHERE session_id = ?")) {
                statement.setLong(1, sessionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM chat_messages WHERE session_id = ?")) {
                statement.setLong(1, sessionId);
                statement.executeUpdate();
            }
            deleteTeamData(connection, sessionId);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            """
                            UPDATE chat_sessions
                            SET context_summary = NULL, context_turns = 0
                            WHERE session_id = ?
                            """)) {
                statement.setLong(1, sessionId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清空会话消息失败：" + e.getMessage(), e);
        }
    }

    /** 会话清空/删除时同步清理团队运行、节点记录与反馈，避免孤儿数据。 */
    private static void deleteTeamData(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        DELETE FROM agent_run_records
                        WHERE run_id IN (SELECT run_id FROM agent_team_runs WHERE session_id = ?)
                        """)) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM agent_team_runs WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM chat_feedback WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
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

    private static ChatToolTrace mapTrace(ResultSet rs) throws SQLException {
        String employeesJson = rs.getString("employees_json");
        List<String> employees =
                employeesJson == null || employeesJson.isBlank()
                        ? List.of()
                        : JsonSupport.fromJson(
                                employeesJson, new TypeReference<List<String>>() {});
        String amountsJson = rs.getString("amounts_json");
        Map<String, BigDecimal> amounts =
                amountsJson == null || amountsJson.isBlank()
                        ? Map.of()
                        : JsonSupport.fromJson(
                                amountsJson,
                                new TypeReference<Map<String, BigDecimal>>() {});
        return new ChatToolTrace(
                rs.getLong("trace_id"),
                rs.getLong("message_id"),
                rs.getLong("session_id"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("agent"),
                rs.getLong("run_id"),
                rs.getString("node_id"),
                rs.getString("args_json"),
                rs.getString("result_json"),
                rs.getBoolean("truncated"),
                rs.getString("result_state"),
                rs.getLong("duration_ms"),
                employees,
                rs.getString("period"),
                rs.getString("department"),
                amounts,
                rs.getObject("created_at", LocalDateTime.class));
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
