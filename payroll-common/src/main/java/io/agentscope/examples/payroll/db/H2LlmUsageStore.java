package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** 模型用量事实的 H2/MySQL 读写：一次模型调用一行，按时间倒序查询。 */
public final class H2LlmUsageStore implements LlmUsageStore {

    private static final String COLUMNS =
            "usage_id, occurred_at, user_id, session_id, agent_id, agent_name, role, stage_key, "
                    + "run_id, model, provider, plane, prompt_tokens, completion_tokens, "
                    + "cached_tokens, total_tokens, latency_ms, success, error_type, reply_id";

    private final PayrollDatabase database;

    public H2LlmUsageStore(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public long insert(LlmUsageRecord record) {
        String sql =
                """
                INSERT INTO llm_usage (occurred_at, user_id, session_id, agent_id, agent_name,
                    role, stage_key, run_id, model, provider, plane, prompt_tokens,
                    completion_tokens, cached_tokens, total_tokens, latency_ms, success,
                    error_type, reply_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setTimestamp(1, Timestamp.valueOf(record.occurredAt()));
            statement.setString(2, record.userId());
            statement.setString(3, record.sessionId());
            statement.setString(4, record.agentId());
            statement.setString(5, record.agentName());
            statement.setString(6, record.role());
            statement.setString(7, record.stageKey());
            statement.setObject(8, record.runId());
            statement.setString(9, record.model());
            statement.setString(10, record.provider());
            statement.setString(11, record.plane());
            statement.setInt(12, record.promptTokens());
            statement.setInt(13, record.completionTokens());
            statement.setInt(14, record.cachedTokens());
            statement.setInt(15, record.totalTokens());
            statement.setLong(16, record.latencyMs());
            statement.setBoolean(17, record.success());
            statement.setString(18, record.errorType());
            statement.setString(19, record.replyId());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入模型用量失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<LlmUsageRecord> find(LlmUsageQuery query, int limit) {
        List<Object> params = new ArrayList<>();
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM llm_usage"
                        + where(query, params)
                        + " ORDER BY occurred_at DESC, usage_id DESC LIMIT ?";
        params.add(Math.max(1, limit));
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<LlmUsageRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询模型用量失败：" + e.getMessage(), e);
        }
    }

    private static String where(LlmUsageQuery query, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("occurred_at >= ?");
        params.add(Timestamp.valueOf(query.from()));
        conditions.add("occurred_at < ?");
        params.add(Timestamp.valueOf(query.to()));
        if (query.model() != null) {
            conditions.add("model = ?");
            params.add(query.model());
        }
        if (query.plane() != null) {
            conditions.add("plane = ?");
            params.add(query.plane());
        }
        if (query.agent() != null) {
            conditions.add("(agent_id = ? OR agent_name = ?)");
            params.add(query.agent());
            params.add(query.agent());
        }
        if (query.userId() != null) {
            conditions.add("user_id = ?");
            params.add(query.userId());
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private static LlmUsageRecord map(ResultSet rs) throws SQLException {
        Timestamp occurredAt = rs.getTimestamp("occurred_at");
        Object runId = rs.getObject("run_id");
        return new LlmUsageRecord(
                rs.getLong("usage_id"),
                occurredAt == null ? null : occurredAt.toLocalDateTime(),
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("agent_id"),
                rs.getString("agent_name"),
                rs.getString("role"),
                rs.getString("stage_key"),
                runId == null ? null : ((Number) runId).longValue(),
                rs.getString("model"),
                rs.getString("provider"),
                rs.getString("plane"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getInt("cached_tokens"),
                rs.getInt("total_tokens"),
                rs.getLong("latency_ms"),
                rs.getBoolean("success"),
                rs.getString("error_type"),
                rs.getString("reply_id"));
    }
}
