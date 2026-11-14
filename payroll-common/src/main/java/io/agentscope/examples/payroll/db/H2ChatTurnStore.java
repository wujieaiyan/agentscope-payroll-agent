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

    /** 未显式配置最长执行时长时的兜底值。 */
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(30);

    private static final String SELECT =
            """
            SELECT turn_id, session_id, user_id, user_message_id, assistant_message_id,
                   status, owner_node, fencing_token, last_seq, error_message,
                   started_at, updated_at, heartbeat_at, deadline_at, finished_at
            FROM chat_turns
            """;

    private final PayrollDatabase database;

    public H2ChatTurnStore(PayrollDatabase database) {
        this.database = database;
    }

    public ChatTurn create(ChatTurn turn) {
        try (Connection connection = database.connection()) {
            insert(connection, turn);
            return turn;
        } catch (SQLException e) {
            throw new IllegalStateException("创建对话轮次失败：" + e.getMessage(), e);
        }
    }

    /**
     * 用户消息与轮次行同一事务写入：崩溃时要么都成功，要么都不留痕，避免「有消息无轮次」。
     */
    public ChatTurn createWithUserMessage(
            String turnId,
            long sessionId,
            long userId,
            String message,
            String ownerNode,
            long fencingToken,
            Duration maxDuration) {
        LocalDateTime now = LocalDateTime.now();
        Duration limit =
                maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                        ? DEFAULT_MAX_DURATION
                        : maxDuration;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                // turn_id 唯一索引只标识终态助手消息；用户消息经 user_message_id 关联。
                long messageId =
                        H2ChatStore.insertMessage(
                                connection,
                                sessionId,
                                H2ChatStore.ROLE_USER,
                                message,
                                null,
                                null,
                                null,
                                now);
                ChatTurn turn =
                        new ChatTurn(
                                turnId,
                                sessionId,
                                userId,
                                messageId,
                                0L,
                                ChatTurn.RUNNING,
                                ownerNode,
                                fencingToken,
                                0L,
                                null,
                                now,
                                now,
                                now,
                                now.plus(limit),
                                null);
                insert(connection, turn);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE chat_sessions SET updated_at = ? WHERE session_id = ?")) {
                    statement.setObject(1, now);
                    statement.setLong(2, sessionId);
                    statement.executeUpdate();
                }
                connection.commit();
                return turn;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("开启对话轮次失败：" + e.getMessage(), e);
        }
    }

    private static void insert(Connection connection, ChatTurn turn) throws SQLException {
        String sql =
                """
                INSERT INTO chat_turns (turn_id, session_id, user_id, user_message_id,
                    assistant_message_id, status, owner_node, fencing_token, last_seq,
                    error_message, started_at, updated_at, heartbeat_at, deadline_at,
                    finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            statement.setObject(13, turn.heartbeatAt());
            statement.setObject(14, turn.deadlineAt());
            statement.setObject(15, turn.finishedAt());
            statement.executeUpdate();
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

    /**
     * 原子分配围栏令牌：每会话一行计数器，靠行锁自增，避免并发或 Redis 降级时拿到相同令牌。
     * 计数器缺失时以已有轮次的最大令牌为基准，保证跨版本升级后仍然单调。
     */
    public long nextFencing(long sessionId) {
        try (Connection connection = database.connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long floor = maxFencing(connection, sessionId);
                long token;
                if (incrementFencing(connection, sessionId, floor) == 0) {
                    token = floor + 1;
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO chat_fencing (session_id, fencing_token)"
                                            + " VALUES (?, ?)")) {
                        statement.setLong(1, sessionId);
                        statement.setLong(2, token);
                        statement.executeUpdate();
                    } catch (SQLException conflict) {
                        if (!isConstraintViolation(conflict)) {
                            throw conflict;
                        }
                        // 另一实例刚插入计数器行：在它提交后的值上继续自增。
                        incrementFencing(connection, sessionId, floor);
                        token = readFencing(connection, sessionId);
                    }
                } else {
                    token = readFencing(connection, sessionId);
                }
                connection.commit();
                return token;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("分配轮次围栏令牌失败：" + e.getMessage(), e);
        }
    }

    private static long maxFencing(Connection connection, long sessionId) throws SQLException {
        String sql =
                "SELECT COALESCE(MAX(fencing_token), 0) FROM chat_turns WHERE session_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static int incrementFencing(Connection connection, long sessionId, long floor)
            throws SQLException {
        String sql =
                """
                UPDATE chat_fencing
                SET fencing_token =
                    CASE WHEN fencing_token < ? THEN ? + 1 ELSE fencing_token + 1 END
                WHERE session_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, floor);
            statement.setLong(2, floor);
            statement.setLong(3, sessionId);
            return statement.executeUpdate();
        }
    }

    private static long readFencing(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT fencing_token FROM chat_fencing WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("围栏计数器缺失：sessionId=" + sessionId);
                }
                return Math.max(1L, rs.getLong(1));
            }
        }
    }

    private static boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
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

    /** 执行端独立心跳：与事件写入解耦，慢模型不再被误判为失联。 */
    public boolean heartbeat(String turnId, long fencingToken) {
        String sql =
                """
                UPDATE chat_turns SET heartbeat_at = ?, updated_at = ?
                WHERE turn_id = ? AND status = 'RUNNING' AND fencing_token = ?
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.setObject(2, now);
            statement.setString(3, turnId);
            statement.setLong(4, fencingToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新轮次心跳失败：" + e.getMessage(), e);
        }
    }

    /** 心跳超时的运行中轮次；是否真正失联还需由调用方结合租约判断。 */
    public List<ChatTurn> findStaleRunning(Duration olderThan, int limit) {
        String sql =
                SELECT
                        + " WHERE status = 'RUNNING'"
                        + " AND COALESCE(heartbeat_at, updated_at) < ?"
                        + " ORDER BY started_at ASC LIMIT ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, LocalDateTime.now().minus(olderThan));
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatTurn> turns = new ArrayList<>();
                while (rs.next()) {
                    turns.add(map(rs));
                }
                return List.copyOf(turns);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失联轮次失败：" + e.getMessage(), e);
        }
    }

    /** 用户消息与轮次同事务后不再产生孤儿；此处兜底清理历史半写数据的异常轮次。 */
    public int expireOrphanedRunning(String reason) {
        String sql =
                """
                UPDATE chat_turns
                SET status = 'EXPIRED', error_message = ?, updated_at = ?, finished_at = ?
                WHERE status = 'RUNNING'
                  AND (user_message_id = 0
                       OR NOT EXISTS (SELECT 1 FROM chat_messages m
                                      WHERE m.message_id = chat_turns.user_message_id))
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setObject(2, now);
            statement.setObject(3, now);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理孤儿轮次失败：" + e.getMessage(), e);
        }
    }

    /** 节点崩溃后把长时间无心跳的运行中轮次标记为过期。 */
    public int expireStale(Duration olderThan, String reason) {
        String sql =
                """
                UPDATE chat_turns
                SET status = 'EXPIRED', error_message = ?, updated_at = ?, finished_at = ?
                WHERE status = 'RUNNING' AND COALESCE(heartbeat_at, updated_at) < ?
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

    /** 绝对截止时间已过的运行中轮次：执行节点整体失联时由扫描统一收尾。 */
    public int expireOverdueRunning(String reason) {
        String sql =
                """
                UPDATE chat_turns
                SET status = 'EXPIRED', error_message = ?, updated_at = ?, finished_at = ?
                WHERE status = 'RUNNING' AND deadline_at IS NOT NULL AND deadline_at < ?
                """;
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setObject(2, now);
            statement.setObject(3, now);
            statement.setObject(4, now);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理超时轮次失败：" + e.getMessage(), e);
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
                rs.getObject("heartbeat_at", LocalDateTime.class),
                rs.getObject("deadline_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
