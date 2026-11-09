package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 团队运行与节点记录的 H2 仓储；归档只做软删除，审计元数据不物理清除。 */
public final class H2AgentTeamStore {

    private static final String RUN_COLUMNS =
            """
            run_id, plan_id, session_id, message_id, user_id, run_mode, task_type, template_id,
            plan_json, catalog_snapshot_json, score_snapshot_json, status, fallback_reason,
            baseline_run_id, fallback_of_run_id, retry_of_run_id, is_primary, total_ms,
            created_at, closed_at, archived_at, deleted_at
            """;

    private static final String RECORD_COLUMNS =
            """
            record_id, run_id, node_id, stage_key, agent_id, agent_role, status, tool_calls,
            tool_errors, validation_passed, output_chars, duration_ms, trace_ids_json,
            input_digest, message_json, error_message, created_at
            """;

    private final PayrollDatabase database;

    public H2AgentTeamStore(PayrollDatabase database) {
        this.database = database;
    }

    public long createRun(AgentTeamRun run) {
        String sql =
                """
                INSERT INTO agent_team_runs (plan_id, session_id, message_id, user_id, run_mode,
                    task_type, template_id, plan_json, catalog_snapshot_json, score_snapshot_json,
                    status, fallback_reason, baseline_run_id, fallback_of_run_id, retry_of_run_id,
                    is_primary, total_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        LocalDateTime now = run.createdAt() == null ? LocalDateTime.now() : run.createdAt();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, run.planId());
            statement.setLong(2, run.sessionId());
            statement.setLong(3, run.messageId());
            statement.setLong(4, run.userId());
            statement.setString(5, run.mode());
            statement.setString(6, run.taskType());
            statement.setString(7, run.templateId());
            statement.setString(8, run.planJson());
            statement.setString(9, run.catalogSnapshotJson());
            statement.setString(10, run.scoreSnapshotJson());
            statement.setString(11, run.status());
            statement.setString(12, run.fallbackReason());
            setNullableLong(statement, 13, run.baselineRunId());
            setNullableLong(statement, 14, run.fallbackOfRunId());
            setNullableLong(statement, 15, run.retryOfRunId());
            statement.setBoolean(16, run.primary());
            statement.setLong(17, run.totalMs());
            statement.setObject(18, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存团队运行失败：" + e.getMessage(), e);
        }
    }

    /** 助手消息落库后回填 message_id，聊天页即可从消息反查运行。 */
    public void bindMessage(long runId, long messageId) {
        String sql = "UPDATE agent_team_runs SET message_id = ? WHERE run_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            statement.setLong(2, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("绑定团队运行消息失败：" + e.getMessage(), e);
        }
    }

    public void finishRun(long runId, String status, String fallbackReason, long totalMs) {
        String sql =
                """
                UPDATE agent_team_runs
                SET status = ?, fallback_reason = ?, total_ms = ?, closed_at = ?
                WHERE run_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, fallbackReason);
            statement.setLong(3, Math.max(0, totalMs));
            statement.setObject(4, LocalDateTime.now());
            statement.setLong(5, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新团队运行状态失败：" + e.getMessage(), e);
        }
    }

    /** 同一消息只保留一条主运行，供聊天页展示；影子/回退/重试仍可按消息查询。 */
    public void markPrimary(long runId) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                long sessionId;
                long messageId;
                try (PreparedStatement select =
                        connection.prepareStatement(
                                "SELECT session_id, message_id FROM agent_team_runs WHERE run_id = ?")) {
                    select.setLong(1, runId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("团队运行不存在：" + runId);
                        }
                        sessionId = rs.getLong(1);
                        messageId = rs.getLong(2);
                    }
                }
                try (PreparedStatement clear =
                        connection.prepareStatement(
                                """
                                UPDATE agent_team_runs SET is_primary = FALSE
                                WHERE session_id = ? AND message_id = ?
                                """)) {
                    clear.setLong(1, sessionId);
                    clear.setLong(2, messageId);
                    clear.executeUpdate();
                }
                try (PreparedStatement mark =
                        connection.prepareStatement(
                                "UPDATE agent_team_runs SET is_primary = TRUE WHERE run_id = ?")) {
                    mark.setLong(1, runId);
                    mark.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("设置主运行失败：" + e.getMessage(), e);
        }
    }

    public Optional<AgentTeamRun> findRun(long runId) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM agent_team_runs WHERE run_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询团队运行失败：" + e.getMessage(), e);
        }
    }

    /** 某条消息下的全部未归档运行，新的在前。 */
    public List<AgentTeamRun> runs(long sessionId, long messageId) {
        String sql =
                "SELECT "
                        + RUN_COLUMNS
                        + """
                         FROM agent_team_runs
                        WHERE session_id = ? AND message_id = ? AND deleted_at IS NULL
                        ORDER BY run_id DESC
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            return mapRuns(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息团队运行失败：" + e.getMessage(), e);
        }
    }

    public List<AgentTeamRun> runsBySession(long sessionId) {
        String sql =
                "SELECT "
                        + RUN_COLUMNS
                        + """
                         FROM agent_team_runs
                        WHERE session_id = ? AND deleted_at IS NULL
                        ORDER BY run_id DESC
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            return mapRuns(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话团队运行失败：" + e.getMessage(), e);
        }
    }

    /** 统计窗口内的影子运行，新的在前；只读审计用。 */
    public List<AgentTeamRun> shadowRunsSince(LocalDateTime since) {
        String sql =
                "SELECT "
                        + RUN_COLUMNS
                        + """
                         FROM agent_team_runs
                        WHERE run_mode = ? AND created_at >= ? AND deleted_at IS NULL
                        ORDER BY run_id DESC
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, AgentTeamRun.MODE_SHADOW);
            statement.setObject(2, since);
            return mapRuns(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("查询影子运行失败：" + e.getMessage(), e);
        }
    }

    /** 批量读取运行，供影子报告对齐基线；不存在的 id 忽略。 */
    public List<AgentTeamRun> findRuns(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(runIds));
        List<AgentTeamRun> runs = new ArrayList<>();
        int chunkSize = 500;
        for (int start = 0; start < ids.size(); start += chunkSize) {
            List<Long> chunk = ids.subList(start, Math.min(start + chunkSize, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql =
                    "SELECT "
                            + RUN_COLUMNS
                            + " FROM agent_team_runs WHERE run_id IN ("
                            + placeholders
                            + ")";
            try (Connection connection = database.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setLong(i + 1, chunk.get(i));
                }
                runs.addAll(mapRuns(statement));
            } catch (SQLException e) {
                throw new IllegalStateException("批量查询团队运行失败：" + e.getMessage(), e);
            }
        }
        return List.copyOf(runs);
    }

    public Optional<AgentTeamRun> primaryRun(long sessionId, long messageId) {        String sql =
                "SELECT "
                        + RUN_COLUMNS
                        + """
                         FROM agent_team_runs
                        WHERE session_id = ? AND message_id = ? AND is_primary = TRUE
                              AND deleted_at IS NULL
                        ORDER BY run_id DESC LIMIT 1
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询主运行失败：" + e.getMessage(), e);
        }
    }

    public long appendRecord(AgentRunRecord record) {
        String sql =
                """
                INSERT INTO agent_run_records (run_id, node_id, stage_key, agent_id, agent_role,
                    status, tool_calls, tool_errors, validation_passed, output_chars, duration_ms,
                    trace_ids_json, input_digest, message_json, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        LocalDateTime now = record.createdAt() == null ? LocalDateTime.now() : record.createdAt();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, record.runId());
            statement.setString(2, record.nodeId());
            statement.setString(3, record.stageKey());
            statement.setString(4, record.agentId());
            statement.setString(5, record.role());
            statement.setString(6, record.status());
            statement.setInt(7, Math.max(0, record.toolCalls()));
            statement.setInt(8, Math.max(0, record.toolErrors()));
            if (record.validationPassed() == null) {
                statement.setNull(9, Types.BOOLEAN);
            } else {
                statement.setBoolean(9, record.validationPassed());
            }
            statement.setInt(10, Math.max(0, record.outputChars()));
            statement.setLong(11, Math.max(0, record.durationMs()));
            statement.setString(12, record.traceIds().isEmpty() ? null : JsonSupport.toJson(record.traceIds()));
            statement.setString(13, record.inputDigest());
            statement.setString(14, record.messageJson());
            statement.setString(15, record.errorMessage());
            statement.setObject(16, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存节点运行记录失败：" + e.getMessage(), e);
        }
    }

    /** 消息落库后回填本次节点产生 trace_id，保证 message → run → node → trace 链路完整。 */
    public void attachTraceIds(long recordId, List<Long> traceIds) {
        String sql = "UPDATE agent_run_records SET trace_ids_json = ? WHERE record_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(
                    1,
                    traceIds == null || traceIds.isEmpty()
                            ? null
                            : JsonSupport.toJson(List.copyOf(traceIds)));
            statement.setLong(2, recordId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("回填节点追溯失败：" + e.getMessage(), e);
        }
    }

    public List<AgentRunRecord> records(long runId) {
        String sql =
                "SELECT "
                        + RECORD_COLUMNS
                        + " FROM agent_run_records WHERE run_id = ? ORDER BY record_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AgentRunRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询节点运行记录失败：" + e.getMessage(), e);
        }
    }

    /** 统计窗口内的节点记录，新的在前；已归档运行不计入评分。 */
    public List<AgentRunRecord> recordsSince(LocalDateTime since) {
        String sql =
                "SELECT "
                        + RECORD_COLUMNS
                        + """
                         FROM agent_run_records
                        WHERE created_at >= ?
                          AND run_id IN (SELECT run_id FROM agent_team_runs WHERE deleted_at IS NULL)
                        ORDER BY record_id DESC
                        """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, since);
            try (ResultSet rs = statement.executeQuery()) {
                List<AgentRunRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询统计窗口节点记录失败：" + e.getMessage(), e);
        }
    }

    /** 反馈消息对应的主运行中真正执行过的智能体，SKIPPED 节点不计入。 */
    public Map<Long, Set<String>> primaryExecutedAgents(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(messageIds));
        int chunkSize = 500;
        for (int start = 0; start < ids.size(); start += chunkSize) {
            List<Long> chunk = ids.subList(start, Math.min(start + chunkSize, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql =
                    """
                    SELECT r.message_id, rec.agent_id
                    FROM agent_run_records rec
                    JOIN agent_team_runs r ON r.run_id = rec.run_id
                    WHERE r.is_primary = TRUE AND r.deleted_at IS NULL
                      AND rec.status <> ?
                      AND rec.agent_id IS NOT NULL
                      AND r.message_id IN (%s)
                    """
                            .formatted(placeholders);
            try (Connection connection = database.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, AgentRunRecord.STATUS_SKIPPED);
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setLong(i + 2, chunk.get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), key -> new LinkedHashSet<>())
                                .add(rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询反馈关联智能体失败：" + e.getMessage(), e);
            }
        }
        return result;
    }

    /** 超过保留期的运行软删除；节点记录保留，供审计按 run_id 继续追溯。 */
    public int archiveExpired(int retentionDays) {
        int days = Math.max(1, retentionDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        LocalDateTime now = LocalDateTime.now();
        String sql =
                """
                UPDATE agent_team_runs
                SET archived_at = ?, deleted_at = ?
                WHERE created_at < ? AND deleted_at IS NULL
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.setObject(2, now);
            statement.setObject(3, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("归档过期团队运行失败：" + e.getMessage(), e);
        }
    }

    private static List<AgentTeamRun> mapRuns(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            List<AgentTeamRun> runs = new ArrayList<>();
            while (rs.next()) {
                runs.add(mapRun(rs));
            }
            return List.copyOf(runs);
        }
    }

    private static AgentTeamRun mapRun(ResultSet rs) throws SQLException {
        return new AgentTeamRun(
                rs.getLong("run_id"),
                rs.getString("plan_id"),
                rs.getLong("session_id"),
                rs.getLong("message_id"),
                rs.getLong("user_id"),
                rs.getString("run_mode"),
                rs.getString("task_type"),
                rs.getString("template_id"),
                rs.getString("plan_json"),
                rs.getString("catalog_snapshot_json"),
                rs.getString("score_snapshot_json"),
                rs.getString("status"),
                rs.getString("fallback_reason"),
                rs.getObject("baseline_run_id", Long.class),
                rs.getObject("fallback_of_run_id", Long.class),
                rs.getObject("retry_of_run_id", Long.class),
                rs.getBoolean("is_primary"),
                rs.getLong("total_ms"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("archived_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class));
    }

    private static AgentRunRecord mapRecord(ResultSet rs) throws SQLException {
        String traceIdsJson = rs.getString("trace_ids_json");
        List<Long> traceIds =
                traceIdsJson == null || traceIdsJson.isBlank()
                        ? List.of()
                        : JsonSupport.fromJson(traceIdsJson, new TypeReference<List<Long>>() {});
        return new AgentRunRecord(
                rs.getLong("record_id"),
                rs.getLong("run_id"),
                rs.getString("node_id"),
                rs.getString("stage_key"),
                rs.getString("agent_id"),
                rs.getString("agent_role"),
                rs.getString("status"),
                rs.getInt("tool_calls"),
                rs.getInt("tool_errors"),
                rs.getObject("validation_passed", Boolean.class),
                rs.getInt("output_chars"),
                rs.getLong("duration_ms"),
                traceIds,
                rs.getString("input_digest"),
                rs.getString("message_json"),
                rs.getString("error_message"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
