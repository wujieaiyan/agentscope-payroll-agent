package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.ActionProposal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 写操作提案的持久化。状态流转全部使用条件 UPDATE（{@code WHERE status = 'PENDING'}），
 * 只有真正更新到的请求才继续执行，因此重复确认与多标签页并发点击天然幂等。
 */
public final class H2ActionProposalStore {

    private static final String SELECT =
            """
            SELECT proposal_id, user_id, session_id, message_id, agent_path, tool_name,
                   params_json, summary, risk_level, safety_source, status, idempotency_key,
                   snapshot_hash, created_at, expires_at, confirmed_by, confirmed_at,
                   executed_at, result_json, error_message
            FROM chat_action_proposals
            """;

    private final PayrollDatabase database;

    public H2ActionProposalStore(PayrollDatabase database) {
        this.database = database;
    }

    public Optional<ActionProposal> find(String proposalId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE proposal_id = ?")) {
            statement.setString(1, proposalId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询提案失败：" + e.getMessage(), e);
        }
    }

    /** 会话内全部提案；供历史消息一次性批量恢复卡片，避免逐条查询。 */
    public List<ActionProposal> forSession(long sessionId) {
        expireDue(sessionId);
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE session_id = ? ORDER BY created_at, proposal_id")) {
            statement.setLong(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ActionProposal> proposals = new ArrayList<>();
                while (rs.next()) {
                    proposals.add(map(rs));
                }
                return List.copyOf(proposals);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话提案失败：" + e.getMessage(), e);
        }
    }

    /** 助手消息落库时与消息同事务写入提案，避免“有卡片无消息”的半条记录。 */
    static void insert(
            Connection connection,
            long sessionId,
            long messageId,
            ActionProposal proposal,
            LocalDateTime now)
            throws SQLException {
        String sql =
                """
                INSERT INTO chat_action_proposals (proposal_id, user_id, session_id, message_id,
                    agent_path, tool_name, params_json, summary, risk_level, safety_source, status,
                    idempotency_key, snapshot_hash, created_at, expires_at, confirmed_by,
                    confirmed_at, executed_at, result_json, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, proposal.proposalId());
            statement.setLong(2, proposal.userId());
            statement.setLong(3, sessionId);
            statement.setLong(4, messageId);
            statement.setString(5, proposal.agentPath());
            statement.setString(6, proposal.toolName());
            setClob(statement, 7, proposal.paramsJson());
            statement.setString(8, proposal.summary());
            statement.setString(9, proposal.riskLevel());
            statement.setString(10, proposal.safetySource());
            statement.setString(11, proposal.status());
            statement.setString(12, proposal.idempotencyKey());
            statement.setString(13, proposal.snapshotHash());
            statement.setObject(14, now);
            statement.setObject(15, proposal.expiresAt());
            if (proposal.confirmedBy() > 0) {
                statement.setLong(16, proposal.confirmedBy());
            } else {
                statement.setNull(16, Types.BIGINT);
            }
            statement.setObject(17, proposal.confirmedAt());
            statement.setObject(18, proposal.executedAt());
            setClob(statement, 19, proposal.resultJson());
            statement.setString(20, proposal.errorMessage());
            statement.executeUpdate();
        }
    }

    /** PENDING → CONFIRMED；返回 true 表示本次请求真正赢得了执行权。 */
    public boolean confirmPending(String proposalId, long userId, String idempotencyKey) {
        String sql =
                """
                UPDATE chat_action_proposals
                SET status = ?, confirmed_by = ?, confirmed_at = ?, idempotency_key = ?
                WHERE proposal_id = ? AND status = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ActionProposal.STATUS_CONFIRMED);
            statement.setLong(2, userId);
            statement.setObject(3, LocalDateTime.now());
            statement.setString(4, idempotencyKey);
            statement.setString(5, proposalId);
            statement.setString(6, ActionProposal.STATUS_PENDING);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("确认提案失败：" + e.getMessage(), e);
        }
    }

    public boolean markExecuted(String proposalId, String resultJson) {
        return transition(
                proposalId,
                ActionProposal.STATUS_CONFIRMED,
                ActionProposal.STATUS_EXECUTED,
                resultJson,
                null);
    }

    public boolean markFailed(String proposalId, String message) {
        return transition(
                proposalId,
                ActionProposal.STATUS_CONFIRMED,
                ActionProposal.STATUS_FAILED,
                null,
                message);
    }

    /** PENDING → CANCELED；已进入 CONFIRMED 的提案不允许再取消。 */
    public boolean cancelPending(String proposalId) {
        String sql =
                "UPDATE chat_action_proposals SET status = ? WHERE proposal_id = ? AND status = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ActionProposal.STATUS_CANCELED);
            statement.setString(2, proposalId);
            statement.setString(3, ActionProposal.STATUS_PENDING);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("取消提案失败：" + e.getMessage(), e);
        }
    }

    public boolean markExpired(String proposalId) {
        String sql =
                "UPDATE chat_action_proposals SET status = ? WHERE proposal_id = ? AND status = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ActionProposal.STATUS_EXPIRED);
            statement.setString(2, proposalId);
            statement.setString(3, ActionProposal.STATUS_PENDING);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("标记提案过期失败：" + e.getMessage(), e);
        }
    }

    /** 历史恢复前把已到期的 PENDING 落为 EXPIRED，避免卡片停留在可点击状态。 */
    public int expireDue(long sessionId) {
        String sql =
                """
                UPDATE chat_action_proposals SET status = ?
                WHERE session_id = ? AND status = ? AND expires_at IS NOT NULL AND expires_at <= ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ActionProposal.STATUS_EXPIRED);
            statement.setLong(2, sessionId);
            statement.setString(3, ActionProposal.STATUS_PENDING);
            statement.setObject(4, LocalDateTime.now());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理过期提案失败：" + e.getMessage(), e);
        }
    }

    /** 会话重置或角色切换时批量作废未确认提案，防止上一角色的动作被新角色执行。 */
    public int cancelPendingForSession(long sessionId) {
        String sql =
                "UPDATE chat_action_proposals SET status = ? WHERE session_id = ? AND status = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ActionProposal.STATUS_CANCELED);
            statement.setLong(2, sessionId);
            statement.setString(3, ActionProposal.STATUS_PENDING);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("取消会话提案失败：" + e.getMessage(), e);
        }
    }

    /** 会话删除时的物理清理；与消息、trace、团队数据同一事务。 */
    static void deleteForSession(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM chat_action_proposals WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
    }

    private boolean transition(
            String proposalId,
            String fromStatus,
            String toStatus,
            String resultJson,
            String errorMessage) {
        String sql =
                """
                UPDATE chat_action_proposals
                SET status = ?, executed_at = ?, result_json = ?, error_message = ?
                WHERE proposal_id = ? AND status = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toStatus);
            statement.setObject(2, LocalDateTime.now());
            setClob(statement, 3, resultJson);
            statement.setString(4, errorMessage);
            statement.setString(5, proposalId);
            statement.setString(6, fromStatus);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("更新提案状态失败：" + e.getMessage(), e);
        }
    }

    private static void setClob(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.CLOB);
        } else {
            statement.setString(index, value);
        }
    }

    private static ActionProposal map(ResultSet rs) throws SQLException {
        return new ActionProposal(
                rs.getString("proposal_id"),
                rs.getLong("user_id"),
                rs.getLong("session_id"),
                rs.getLong("message_id"),
                rs.getString("agent_path"),
                rs.getString("tool_name"),
                rs.getString("params_json"),
                rs.getString("summary"),
                rs.getString("risk_level"),
                rs.getString("safety_source"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("snapshot_hash"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getLong("confirmed_by"),
                rs.getObject("confirmed_at", LocalDateTime.class),
                rs.getObject("executed_at", LocalDateTime.class),
                rs.getString("result_json"),
                rs.getString("error_message"));
    }
}
