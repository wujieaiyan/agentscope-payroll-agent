package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.devteam.DevDelivery;
import io.agentscope.examples.payroll.devteam.DevDeliveryPatch;
import io.agentscope.examples.payroll.devteam.DevDeliveryStatus;
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
 * G3 交付申请存储（P2-1）：一行一次交付，补丁引用与 PR 描述以 JSON/文本保存；
 * 条件更新保证同一申请只被执行一次，重复确认返回当前状态。
 */
public final class DevDeliveryStore {

    public static final int MAX_TITLE = 200;
    public static final int MAX_NOTE = 500;
    public static final int MAX_BRANCH = 100;
    private static final int MAX_PLAN_ID = 64;
    private static final int MAX_BODY = 20_000;
    private static final Pattern PLAN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern BRANCH =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<DevDeliveryPatch>> PATCH_LIST = new TypeReference<>() {};

    private static final String SELECT =
            """
            SELECT delivery_id, plan_id, branch, base_commit, pr_title, pr_body, status,
                   patches_json, commit_id, decided_by, note, created_at, decided_at
            FROM dev_deliveries
            """;

    private final PayrollDatabase database;

    public DevDeliveryStore(PayrollDatabase database) {
        this.database = database;
    }

    public DevDelivery create(
            String planId,
            String branch,
            String baseCommit,
            String prTitle,
            String prBody,
            List<DevDeliveryPatch> patches) {
        String plan = requirePlan(planId);
        String branchValue = requireBranch(branch);
        if (patches == null || patches.isEmpty()) {
            throw new PayrollException("交付申请至少需要一份补丁");
        }
        String deliveryId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String sql =
                """
                INSERT INTO dev_deliveries
                    (delivery_id, plan_id, branch, base_commit, pr_title, pr_body, status,
                     patches_json, commit_id, decided_by, note, created_at, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', '', '', ?, NULL)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deliveryId);
            statement.setString(2, plan);
            statement.setString(3, branchValue);
            statement.setString(4, trimTo(baseCommit, 64));
            statement.setString(5, trimTo(prTitle, MAX_TITLE));
            statement.setString(6, trimTo(prBody, MAX_BODY));
            statement.setString(7, DevDeliveryStatus.PENDING.wireName());
            statement.setString(8, toJson(patches));
            statement.setTimestamp(9, Timestamp.valueOf(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("创建交付申请失败：" + e.getMessage(), e);
        }
        return new DevDelivery(
                deliveryId,
                plan,
                branchValue,
                trimTo(baseCommit, 64),
                trimTo(prTitle, MAX_TITLE),
                trimTo(prBody, MAX_BODY),
                DevDeliveryStatus.PENDING,
                List.copyOf(patches),
                "",
                "",
                "",
                now,
                null);
    }

    public Optional<DevDelivery> find(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE delivery_id = ?")) {
            statement.setString(1, deliveryId.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取交付申请失败：" + e.getMessage(), e);
        }
    }

    public List<DevDelivery> list(String planId) {
        String plan = requirePlan(planId);
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE plan_id = ? ORDER BY created_at DESC")) {
            statement.setString(1, plan);
            try (ResultSet rows = statement.executeQuery()) {
                List<DevDelivery> deliveries = new ArrayList<>();
                while (rows.next()) {
                    deliveries.add(map(rows));
                }
                return List.copyOf(deliveries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取交付申请失败：" + e.getMessage(), e);
        }
    }

    /** 条件更新：仅 PENDING 可决策；返回 false 表示已被其他请求决策。 */
    public boolean decide(
            String deliveryId,
            DevDeliveryStatus status,
            String commitId,
            String decidedBy,
            String note) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new PayrollException("交付申请 id 不能为空");
        }
        if (status == null || status == DevDeliveryStatus.PENDING) {
            throw new PayrollException("决策状态必须为 approved/rejected/stale");
        }
        String sql =
                """
                UPDATE dev_deliveries
                SET status = ?, commit_id = ?, decided_by = ?, note = ?, decided_at = ?
                WHERE delivery_id = ? AND status = 'pending'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.wireName());
            statement.setString(2, trimTo(commitId, 64));
            statement.setString(3, trimTo(decidedBy, 64));
            statement.setString(4, trimTo(note, MAX_NOTE));
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(6, deliveryId.trim());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新交付申请失败：" + e.getMessage(), e);
        }
    }

    private static DevDelivery map(ResultSet rows) throws SQLException {
        return new DevDelivery(
                rows.getString("delivery_id"),
                rows.getString("plan_id"),
                rows.getString("branch"),
                rows.getString("base_commit"),
                rows.getString("pr_title"),
                rows.getString("pr_body"),
                DevDeliveryStatus.parse(rows.getString("status"))
                        .orElse(DevDeliveryStatus.PENDING),
                fromJson(rows.getString("patches_json")),
                rows.getString("commit_id"),
                rows.getString("decided_by"),
                rows.getString("note"),
                rows.getObject("created_at", LocalDateTime.class),
                rows.getObject("decided_at", LocalDateTime.class));
    }

    private static String toJson(List<DevDeliveryPatch> patches) {
        try {
            return JSON.writeValueAsString(patches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化交付补丁失败：" + e.getMessage(), e);
        }
    }

    private static List<DevDeliveryPatch> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<DevDeliveryPatch> patches = JSON.readValue(value, PATCH_LIST);
            return patches == null ? List.of() : List.copyOf(patches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析交付补丁失败：" + e.getMessage(), e);
        }
    }

    private static String requirePlan(String planId) {
        String plan = planId == null ? "" : planId.trim();
        if (plan.isEmpty() || plan.length() > MAX_PLAN_ID || !PLAN_ID.matcher(plan).matches()) {
            throw new PayrollException("计划 id 非法：" + planId);
        }
        return plan;
    }

    public static String requireBranch(String branch) {
        String value = branch == null ? "" : branch.trim();
        if (value.isEmpty()
                || value.length() > MAX_BRANCH
                || !BRANCH.matcher(value).matches()
                || value.contains("..")
                || value.contains("//")
                || value.endsWith("/")
                || value.endsWith(".")
                || value.endsWith(".lock")) {
            throw new PayrollException("交付分支名非法：" + branch);
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("main") || normalized.equals("master") || normalized.equals("head")) {
            throw new PayrollException("交付分支不能是默认分支：" + value);
        }
        return value;
    }

    private static String trimTo(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
