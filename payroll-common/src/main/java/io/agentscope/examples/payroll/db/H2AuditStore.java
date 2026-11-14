package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 审计日志的 H2 读写：按时间倒序分页，支持动作/资源/结果/关键字组合过滤。 */
public final class H2AuditStore {

    private static final String COLUMNS =
            "audit_id, occurred_at, actor_id, actor_name, action, resource_type, resource_id, "
                    + "before_json, after_json, payload_json, ip, user_agent, result, status_code, "
                    + "message, trace_id";

    private final PayrollDatabase database;

    public H2AuditStore(PayrollDatabase database) {
        this.database = database;
    }

    public long insert(AuditEntry entry) {
        String sql =
                """
                INSERT INTO audit_log (occurred_at, actor_id, actor_name, action, resource_type,
                    resource_id, before_json, after_json, payload_json, ip, user_agent, result,
                    status_code, message, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setObject(1, entry.occurredAt());
            statement.setObject(2, entry.actorId());
            statement.setString(3, entry.actorName());
            statement.setString(4, entry.action());
            statement.setString(5, entry.resourceType());
            statement.setString(6, entry.resourceId());
            statement.setString(7, entry.beforeJson());
            statement.setString(8, entry.afterJson());
            statement.setString(9, entry.payloadJson());
            statement.setString(10, entry.ip());
            statement.setString(11, entry.userAgent());
            statement.setString(12, entry.result());
            statement.setObject(13, entry.statusCode());
            statement.setString(14, entry.message());
            statement.setString(15, entry.traceId());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入审计日志失败：" + e.getMessage(), e);
        }
    }

    public List<AuditEntry> find(AuditQuery query) {
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM audit_log"
                        + where
                        + " ORDER BY occurred_at DESC, audit_id DESC LIMIT ? OFFSET ?";
        params.add(query.size());
        params.add(query.offset());
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(map(rs));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审计日志失败：" + e.getMessage(), e);
        }
    }

    public long count(AuditQuery query) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM audit_log" + where(query, params);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计审计日志失败：" + e.getMessage(), e);
        }
    }

    public int purgeBefore(LocalDateTime cutoff) {
        String sql = "DELETE FROM audit_log WHERE occurred_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理审计日志失败：" + e.getMessage(), e);
        }
    }

    private static String where(AuditQuery query, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (query.from() != null) {
            conditions.add("occurred_at >= ?");
            params.add(query.from());
        }
        if (query.to() != null) {
            conditions.add("occurred_at <= ?");
            params.add(query.to());
        }
        addLike(conditions, params, "actor_name", query.actor());
        addLike(conditions, params, "action", query.action());
        addLike(conditions, params, "resource_type", query.resourceType());
        addLike(conditions, params, "resource_id", query.resourceId());
        if (query.result() != null && !query.result().isBlank()) {
            conditions.add("result = ?");
            params.add(query.result().trim().toUpperCase());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
            conditions.add(
                    "(action LIKE ? ESCAPE '\\' OR resource_id LIKE ? ESCAPE '\\' "
                            + "OR message LIKE ? ESCAPE '\\' OR actor_name LIKE ? ESCAPE '\\')");
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static void addLike(
            List<String> conditions, List<Object> params, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        conditions.add(column + " LIKE ? ESCAPE '\\'");
        params.add("%" + escapeLike(value.trim()) + "%");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private static AuditEntry map(ResultSet rs) throws SQLException {
        return new AuditEntry(
                rs.getLong("audit_id"),
                rs.getObject("occurred_at", LocalDateTime.class),
                rs.getObject("actor_id", Long.class),
                rs.getString("actor_name"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getString("payload_json"),
                rs.getString("ip"),
                rs.getString("user_agent"),
                rs.getString("result"),
                rs.getObject("status_code", Integer.class),
                rs.getString("message"),
                rs.getString("trace_id"));
    }
}
