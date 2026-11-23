package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.devteam.DevApprovalBatch;
import io.agentscope.examples.payroll.devteam.DevApprovalItem;
import io.agentscope.examples.payroll.devteam.DevApprovalStatus;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * G2 审批批次存储（P0-8）：一个批次一行，条目以 JSON 保存，条件更新保证只决策一次。
 *
 * <p>重复确认/打回返回当前状态，不重复执行；批次一旦离开 PENDING 不再被覆盖。
 */
public final class DevApprovalStore {

    public static final int MAX_SUMMARY = 500;
    public static final int MAX_NOTE = 500;
    private static final int MAX_PLAN_ID = 64;
    private static final Pattern PLAN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<DevApprovalItem>> ITEM_LIST = new TypeReference<>() {};

    private static final String SELECT =
            """
            SELECT batch_id, plan_id, summary, status, items_json, decided_by, note,
                   created_at, decided_at
            FROM dev_approval_batches
            """;

    private final PayrollDatabase database;

    public DevApprovalStore(PayrollDatabase database) {
        this.database = database;
    }

    public DevApprovalBatch create(String planId, String summary, List<DevApprovalItem> items) {
        String plan = requirePlan(planId);
        if (items == null || items.isEmpty()) {
            throw new PayrollException("审批批次至少需要一个补丁条目");
        }
        String batchId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String sql =
                """
                INSERT INTO dev_approval_batches
                    (batch_id, plan_id, summary, status, items_json, decided_by, note,
                     created_at, decided_at)
                VALUES (?, ?, ?, ?, ?, '', '', ?, NULL)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, batchId);
            statement.setString(2, plan);
            statement.setString(3, trimTo(summary, MAX_SUMMARY));
            statement.setString(4, DevApprovalStatus.PENDING.wireName());
            statement.setString(5, toJson(items));
            statement.setTimestamp(6, Timestamp.valueOf(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("创建审批批次失败：" + e.getMessage(), e);
        }
        return new DevApprovalBatch(
                batchId,
                plan,
                trimTo(summary, MAX_SUMMARY),
                DevApprovalStatus.PENDING,
                List.copyOf(items),
                "",
                "",
                now,
                null);
    }

    public Optional<DevApprovalBatch> find(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE batch_id = ?")) {
            statement.setString(1, batchId.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取审批批次失败：" + e.getMessage(), e);
        }
    }

    public List<DevApprovalBatch> list(String planId) {
        String plan = requirePlan(planId);
        return query(SELECT + " WHERE plan_id = ? ORDER BY created_at DESC", plan);
    }

    public List<DevApprovalBatch> listPending(String planId) {
        String plan = requirePlan(planId);
        return query(
                SELECT + " WHERE plan_id = ? AND status = 'pending' ORDER BY created_at DESC",
                plan);
    }

    /** 条件更新：仅 PENDING 批次可决策；返回 false 表示已被其他请求决策。 */
    public boolean decide(
            String batchId,
            DevApprovalStatus status,
            List<DevApprovalItem> items,
            String decidedBy,
            String note) {
        if (batchId == null || batchId.isBlank()) {
            throw new PayrollException("批次 id 不能为空");
        }
        if (status == null || status == DevApprovalStatus.PENDING) {
            throw new PayrollException("决策状态必须为 approved/rejected/stale");
        }
        if (items == null || items.isEmpty()) {
            throw new PayrollException("审批条目不能为空");
        }
        String sql =
                """
                UPDATE dev_approval_batches
                SET status = ?, items_json = ?, decided_by = ?, note = ?, decided_at = ?
                WHERE batch_id = ? AND status = 'pending'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.wireName());
            statement.setString(2, toJson(items));
            statement.setString(3, trimTo(decidedBy, 64));
            statement.setString(4, trimTo(note, MAX_NOTE));
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(6, batchId.trim());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新审批批次失败：" + e.getMessage(), e);
        }
    }

    private List<DevApprovalBatch> query(String sql, String planId) {
        List<DevApprovalBatch> batches = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    batches.add(map(rows));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取审批批次失败：" + e.getMessage(), e);
        }
        return List.copyOf(batches);
    }

    private static DevApprovalBatch map(ResultSet rows) throws SQLException {
        return new DevApprovalBatch(
                rows.getString("batch_id"),
                rows.getString("plan_id"),
                rows.getString("summary"),
                DevApprovalStatus.parse(rows.getString("status"))
                        .orElse(DevApprovalStatus.PENDING),
                fromJson(rows.getString("items_json")),
                rows.getString("decided_by"),
                rows.getString("note"),
                rows.getObject("created_at", LocalDateTime.class),
                rows.getObject("decided_at", LocalDateTime.class));
    }

    private static String toJson(List<DevApprovalItem> items) {
        try {
            return JSON.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化审批条目失败：" + e.getMessage(), e);
        }
    }

    private static List<DevApprovalItem> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<DevApprovalItem> items = JSON.readValue(value, ITEM_LIST);
            return items == null ? List.of() : List.copyOf(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析审批条目失败：" + e.getMessage(), e);
        }
    }

    private static String requirePlan(String planId) {
        String plan = planId == null ? "" : planId.trim();
        if (plan.isEmpty() || plan.length() > MAX_PLAN_ID || !PLAN_ID.matcher(plan).matches()) {
            throw new PayrollException("计划 id 非法：" + planId);
        }
        return plan;
    }

    private static String trimTo(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
