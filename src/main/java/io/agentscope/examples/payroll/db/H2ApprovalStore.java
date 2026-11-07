package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.ApprovalFlow;
import io.agentscope.examples.payroll.domain.PayrollApprovalEvent;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 审批链配置、批次审批状态与审批轨迹的持久化。 */
public final class H2ApprovalStore {

    private static final int MAX_PRIORITY = 1000;

    private final PayrollDatabase database;

    public H2ApprovalStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<ApprovalFlow> flows() {
        String sql = "SELECT * FROM approval_flows ORDER BY enabled DESC, flow_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<ApprovalFlow> flows = new ArrayList<>();
            while (rs.next()) {
                flows.add(mapFlow(rs));
            }
            return List.copyOf(flows);
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批链失败：" + e.getMessage(), e);
        }
    }

    public Optional<ApprovalFlow> findFlow(long flowId) {
        String sql = "SELECT * FROM approval_flows WHERE flow_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, flowId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapFlow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批链失败：" + e.getMessage(), e);
        }
    }

    /** 部门专属流程优先，其次按优先级从高到低，最后按创建顺序。 */
    public Optional<ApprovalFlow> selectFlow(String scope, BigDecimal amount) {
        return flows().stream()
                .filter(ApprovalFlow::enabled)
                .filter(flow -> flow.covers(scope, amount))
                .sorted(
                        Comparator.comparing((ApprovalFlow flow) -> !flow.isDepartmentScoped())
                                .thenComparing(
                                        Comparator.comparingInt(ApprovalFlow::priority).reversed())
                                .thenComparingLong(ApprovalFlow::flowId))
                .findFirst();
    }

    public ApprovalFlow createFlow(FlowDraft draft) {
        FlowDraft normalized = validate(draft);
        if (normalized.enabled()) {
            requireScopeAvailable(normalized.scopeType(), normalized.scopeValue(), null);
        }
        String sql =
                """
                INSERT INTO approval_flows
                    (name, scope_type, scope_value, min_amount, max_amount, priority, enabled,
                     description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long flowId;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            LocalDateTime now = LocalDateTime.now();
            bindFlow(statement, normalized, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                flowId = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建审批链失败：" + e.getMessage(), e);
        }
        return findFlow(flowId).orElseThrow(() -> new IllegalStateException("审批链保存后无法读取"));
    }

    public ApprovalFlow updateFlow(long flowId, FlowDraft draft) {
        findFlow(flowId).orElseThrow(() -> new PayrollException("审批链不存在：" + flowId));
        FlowDraft normalized = validate(draft);
        if (normalized.enabled()) {
            requireScopeAvailable(normalized.scopeType(), normalized.scopeValue(), flowId);
        }
        String sql =
                """
                UPDATE approval_flows
                SET name = ?, scope_type = ?, scope_value = ?, min_amount = ?, max_amount = ?,
                    priority = ?, enabled = ?, description = ?, updated_at = ?
                WHERE flow_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized.name());
            statement.setString(2, normalized.scopeType());
            statement.setString(3, normalized.scopeValue());
            statement.setBigDecimal(4, normalized.minAmount());
            if (normalized.maxAmount() == null) {
                statement.setNull(5, Types.DECIMAL);
            } else {
                statement.setBigDecimal(5, normalized.maxAmount());
            }
            statement.setInt(6, normalized.priority());
            statement.setBoolean(7, normalized.enabled());
            statement.setString(8, normalized.description());
            statement.setObject(9, LocalDateTime.now());
            statement.setLong(10, flowId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新审批链失败：" + e.getMessage(), e);
        }
        return findFlow(flowId).orElseThrow();
    }

    public void deleteFlow(long flowId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM approval_flows WHERE flow_id = ?")) {
            statement.setLong(1, flowId);
            if (statement.executeUpdate() == 0) {
                throw new PayrollException("审批链不存在：" + flowId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除审批链失败：" + e.getMessage(), e);
        }
    }

    public boolean markSubmitted(
            long runId,
            ApprovalFlow flow,
            Long actorId,
            String actorName,
            String comment,
            String snapshotHash) {
        String sql =
                """
                UPDATE payroll_runs
                SET status = 'PENDING_APPROVAL', approval_flow_id = ?, approval_flow_name = ?,
                    submitted_by_id = ?, submitted_by_name = ?, submitted_at = ?,
                    submit_comment = ?, snapshot_hash = ?,
                    approved_by_id = NULL, approved_by_name = NULL, approved_at = NULL,
                    approval_comment = NULL, rejected_by_id = NULL, rejected_by_name = NULL,
                    rejected_at = NULL, rejection_reason = NULL
                WHERE run_id = ? AND status IN ('DRAFT', 'REJECTED')
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, flow.flowId());
            statement.setString(2, flow.name());
            setNullableLong(statement, 3, actorId);
            statement.setString(4, actorName);
            statement.setObject(5, LocalDateTime.now());
            setNullableString(statement, 6, comment);
            statement.setString(7, snapshotHash);
            statement.setLong(8, runId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("提交审批失败：" + e.getMessage(), e);
        }
    }

    public boolean markApproved(
            long runId,
            Long actorId,
            String actorName,
            String comment,
            String snapshotHash) {
        String sql =
                """
                UPDATE payroll_runs
                SET status = 'APPROVED', approved_by_id = ?, approved_by_name = ?,
                    approved_at = ?, approval_comment = ?
                WHERE run_id = ? AND status = 'PENDING_APPROVAL' AND snapshot_hash = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullableLong(statement, 1, actorId);
            statement.setString(2, actorName);
            statement.setObject(3, LocalDateTime.now());
            setNullableString(statement, 4, comment);
            statement.setLong(5, runId);
            statement.setString(6, snapshotHash);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("审批批次失败：" + e.getMessage(), e);
        }
    }

    public boolean markRejected(
            long runId, Long actorId, String actorName, String reason) {
        String sql =
                """
                UPDATE payroll_runs
                SET status = 'REJECTED', rejected_by_id = ?, rejected_by_name = ?,
                    rejected_at = ?, rejection_reason = ?,
                    approved_by_id = NULL, approved_by_name = NULL, approved_at = NULL,
                    approval_comment = NULL
                WHERE run_id = ? AND status = 'PENDING_APPROVAL'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullableLong(statement, 1, actorId);
            statement.setString(2, actorName);
            statement.setObject(3, LocalDateTime.now());
            statement.setString(4, reason);
            statement.setLong(5, runId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("驳回批次失败：" + e.getMessage(), e);
        }
    }

    public void recordEvent(
            long runId,
            String action,
            Long actorId,
            String actorName,
            ApprovalFlow flow,
            String comment,
            String snapshotHash) {
        String sql =
                """
                INSERT INTO payroll_approval_events
                    (run_id, action, actor_id, actor_name, flow_id, flow_name, comment,
                     snapshot_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, action);
            setNullableLong(statement, 3, actorId);
            statement.setString(4, actorName);
            if (flow == null) {
                statement.setNull(5, Types.BIGINT);
                statement.setNull(6, Types.VARCHAR);
            } else {
                statement.setLong(5, flow.flowId());
                statement.setString(6, flow.name());
            }
            setNullableString(statement, 7, comment);
            setNullableString(statement, 8, snapshotHash);
            statement.setObject(9, LocalDateTime.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录审批轨迹失败：" + e.getMessage(), e);
        }
    }

    public List<PayrollApprovalEvent> events(long runId) {
        String sql =
                "SELECT * FROM payroll_approval_events WHERE run_id = ? ORDER BY event_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PayrollApprovalEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(
                            new PayrollApprovalEvent(
                                    rs.getLong("event_id"),
                                    rs.getLong("run_id"),
                                    rs.getString("action"),
                                    nullableLong(rs, "actor_id"),
                                    rs.getString("actor_name"),
                                    nullableLong(rs, "flow_id"),
                                    rs.getString("flow_name"),
                                    rs.getString("comment"),
                                    rs.getString("snapshot_hash"),
                                    rs.getObject("created_at", LocalDateTime.class)));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批轨迹失败：" + e.getMessage(), e);
        }
    }

    public boolean departmentExists(String name) {
        String sql = "SELECT 1 FROM departments WHERE LOWER(name) = LOWER(?)";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验部门失败：" + e.getMessage(), e);
        }
    }

    private void requireScopeAvailable(String scopeType, String scopeValue, Long excludeId) {
        String sql =
                """
                SELECT name FROM approval_flows
                WHERE enabled = TRUE AND scope_type = ? AND scope_value = ?
                """
                        + (excludeId == null ? "" : " AND flow_id <> ?");
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeType);
            statement.setString(2, scopeValue);
            if (excludeId != null) {
                statement.setLong(3, excludeId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new PayrollException(
                            "该范围已存在生效审批链：" + rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验审批链范围失败：" + e.getMessage(), e);
        }
    }

    private FlowDraft validate(FlowDraft draft) {
        if (draft == null) {
            throw new PayrollException("请填写审批链信息");
        }
        String name = requireText(draft.name(), "审批链名称", 64);
        String scopeType = normalizeScopeType(draft.scopeType());
        String scopeValue;
        if (ApprovalFlow.GLOBAL.equals(scopeType)) {
            scopeValue = "*";
        } else {
            scopeValue = requireText(draft.scopeValue(), "适用部门", 64);
            if (!departmentExists(scopeValue)) {
                throw new PayrollException("部门不存在：" + scopeValue);
            }
        }
        BigDecimal minAmount =
                draft.minAmount() == null
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : draft.minAmount().setScale(2, RoundingMode.HALF_UP);
        if (minAmount.signum() < 0) {
            throw new PayrollException("金额下限不能为负数");
        }
        BigDecimal maxAmount =
                draft.maxAmount() == null
                        ? null
                        : draft.maxAmount().setScale(2, RoundingMode.HALF_UP);
        if (maxAmount != null && maxAmount.compareTo(minAmount) < 0) {
            throw new PayrollException("金额上限不能小于下限");
        }
        int priority = draft.priority();
        if (priority < 0 || priority > MAX_PRIORITY) {
            throw new PayrollException("优先级需在 0-" + MAX_PRIORITY + " 之间");
        }
        String description = draft.description() == null ? "" : draft.description().trim();
        if (description.length() > 200) {
            throw new PayrollException("说明不能超过 200 个字符");
        }
        return new FlowDraft(
                name,
                scopeType,
                scopeValue,
                minAmount,
                maxAmount,
                priority,
                draft.enabled(),
                description);
    }

    private static String normalizeScopeType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ApprovalFlow.GLOBAL.equals(value) && !ApprovalFlow.DEPARTMENT.equals(value)) {
            throw new PayrollException("适用范围需为 GLOBAL 或 DEPARTMENT");
        }
        return value;
    }

    private static String requireText(String value, String label, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new PayrollException(label + "不能为空");
        }
        if (text.length() > max) {
            throw new PayrollException(label + "不能超过 " + max + " 个字符");
        }
        return text;
    }

    private static void bindFlow(
            PreparedStatement statement, FlowDraft flow, LocalDateTime now) throws SQLException {
        statement.setString(1, flow.name());
        statement.setString(2, flow.scopeType());
        statement.setString(3, flow.scopeValue());
        statement.setBigDecimal(4, flow.minAmount());
        if (flow.maxAmount() == null) {
            statement.setNull(5, Types.DECIMAL);
        } else {
            statement.setBigDecimal(5, flow.maxAmount());
        }
        statement.setInt(6, flow.priority());
        statement.setBoolean(7, flow.enabled());
        statement.setString(8, flow.description());
        statement.setObject(9, now);
        statement.setObject(10, now);
    }

    private static ApprovalFlow mapFlow(ResultSet rs) throws SQLException {
        return new ApprovalFlow(
                rs.getLong("flow_id"),
                rs.getString("name"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getBigDecimal("min_amount"),
                rs.getBigDecimal("max_amount"),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static void setNullableLong(
            PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableString(
            PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** 审批链创建/更新输入；enabled 为 null 时按启用处理。 */
    public record FlowDraft(
            String name,
            String scopeType,
            String scopeValue,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int priority,
            boolean enabled,
            String description) {
    }
}
