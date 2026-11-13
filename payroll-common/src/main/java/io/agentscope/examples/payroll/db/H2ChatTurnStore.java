package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.ChatTurn;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 轮次表持久化。所有状态更新都带围栏令牌，避免旧持有者覆盖新持有者的结果。
 */
public final class H2ChatTurnStore {

    private static final String SELECT =
            """
            SELECT turn_id, session_id, user_id, user_message_id, assistant_message_id,
                   status, owner_node, fencing_token, last_seq, error_message,
                   started_at, updated_at, finished_at
            FROM chat_turns
            """;

    private final PayrollDatabase database;

    public H2ChatTurnStore(PayrollDatabase database) {
        this.database = database;
    }

    public ChatTurn create(ChatTurn turn) {
        String sql =
                """
                INSERT INTO chat_turns (turn_id, session_id, user_id, user_message_id,
                    assistant_message_id, status, owner_node, fencing_token, last_seq,
                    error_message, started_at, updated_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turn.turnId());
            statement.setLong(2, turn.sessionId());
            statement.setLong(3, turn.userId());
            statement.setLong(4, turn.userMessageId());
            statement.setLong(5, turn.assistantMessageId());
            statement.setString(6, turn.status());
            statement.setString(7, turn.ownerNode());
            statement.setLong(8, turn.fencingToken());
            statement.setLong(9, turn.lastSeq());
            statement.setString(10, turn.errorMessage());
            statement.setObject(11, turn.startedAt());
            statement.setObject(12, turn.updatedAt());
            statement.setObject(13, turn.finishedAt());
            statement.executeUpdate();
            return turn;
        } catch (SQLException e) {
            throw new IllegalStateException("创建对话轮次失败：" + e.getMessage(), e);
        }
    }

    public Optional<ChatTurn> find(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE turn_id = ?")) {
            statement.setString(1, turnId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询对话轮次失败：" + e.getMessage(), e);
        }
    }

    public Optional<ChatTurn> findRunning(long sessionId) {
        String sql =
                SELECT
                        + " WHERE session_id = ? AND status = 'RUNNING'"
                        + " ORDER BY started_at DESC LIMIT 1";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询进行中的轮次失败：" + e.getMessage(), e);
        }
    }

    public List<ChatTurn> recent(long sessionId, int limit) {
        String sql = SELECT + " WHERE session_id = ? ORDER BY started_at DESC LIMIT ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatTurn> turns = new ArrayList<>();
                while (rs.next()) {
                    turns.add(map(rs));
                }
                return List.copyOf(turns);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话轮次失败：" + e.getMessage(), e);
        }
    }

    public long nextFencing(long sessionId) {
        String sql =
                "SELECT COALESCE(MAX(fencing_token), 0) + 1 FROM chat_turns WHERE session_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Math.max(1L, rs.getLong(1)) : 1L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("分配轮次围栏令牌失败：" + e.getMessage(), e);
        }
    }

    /** 仅当轮次仍在运行且令牌匹配时更新事件序号。 */
    public boolean updateLastSeq(String turnId, long fencingToken, long seq) {
        String sql =
                """
                UPDATE chat_turns SET last_seq = ?, updated_at = ?
                WHERE turn_id = ? AND status = 'RUNNING' AND fencing_token = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seq);
            statement.setObject(2, LocalDateTime.now());
            statement.setString(3, turnId);
            statement.setLong(4, fencingToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新轮次事件序号失败：" + e.getMessage(), e);
        }
    }

    /** 结束轮次；返回 false 表示已被其它持有者接管或已结束。 */
    public boolean finish(
            String turnId,
            long fencingToken,
            String status,
            long assistantMessageId,
            String errorMessage) {
        String sql =
                """
                UPDATE chat_turns
                SET status = ?, assistant_message_id = ?, error_message = ?,
                    updated_at = ?, finished_at = ?
                WHERE turn_id = ? AND status = 'RUNNING' AND fencing_token = ?
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, assistantMessageId);
            statement.setString(3, errorMessage);
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.setString(6, turnId);
            statement.setLong(7, fencingToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("结束对话轮次失败：" + e.getMessage(), e);
        }
    }

    /** 节点崩溃后把长时间无心跳的运行中轮次标记为过期。 */
    public int expireStale(Duration olderThan, String reason) {
        String sql =
                """
                UPDATE chat_turns
                SET status = 'EXPIRED', error_message = ?, updated_at = ?, finished_at = ?
                WHERE status = 'RUNNING' AND updated_at < ?
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setObject(2, now);
            statement.setObject(3, now);
            statement.setObject(4, now.minus(olderThan));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理过期轮次失败：" + e.getMessage(), e);
        }
    }

    public int cleanup(int retentionDays) {
        String sql = "DELETE FROM chat_turns WHERE started_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, LocalDateTime.now().minusDays(Math.max(1, retentionDays)));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理历史轮次失败：" + e.getMessage(), e);
        }
    }

    /** 会话删除/清空时级联清理，避免孤儿轮次。 */
    public static void deleteForSession(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM chat_turns WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
    }

    private static ChatTurn map(ResultSet rs) throws SQLException {
        return new ChatTurn(
                rs.getString("turn_id"),
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getLong("user_message_id"),
                rs.getLong("assistant_message_id"),
                rs.getString("status"),
                rs.getString("owner_node"),
                rs.getLong("fencing_token"),
                rs.getLong("last_seq"),
                rs.getString("error_message"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
