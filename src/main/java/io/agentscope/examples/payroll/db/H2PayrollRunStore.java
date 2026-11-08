package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollApproval;
import io.agentscope.examples.payroll.domain.PayrollEmailResult;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PayrollRunFailure;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 工资批次、明细与邮件投递的持久化。金额与结果 JSON 一旦入库即作为审计事实保存。 */
public final class H2PayrollRunStore {

    private final PayrollDatabase database;

    public H2PayrollRunStore(PayrollDatabase database) {
        this.database = database;
    }

    public PayrollRun createDraft(
            String period,
            String scope,
            List<PayrollRunItem> items,
            List<PayrollRunFailure> failures) {
        BigDecimal totalGross = sum(items, PayrollResult::grossPay);
        BigDecimal totalDeductions = sum(items, PayrollResult::totalDeductions);
        BigDecimal totalNet = sum(items, PayrollResult::netPay);
        BigDecimal totalEmployerCost = sum(items, PayrollResult::employerCost);
        String runSql =
                """
                INSERT INTO payroll_runs (period, scope, status, employee_count, total_gross,
                    total_deductions, total_net, total_employer_cost, created_at)
                VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                long runId;
                try (PreparedStatement statement =
                                connection.prepareStatement(
                                        runSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, period);
                    statement.setString(2, scope);
                    statement.setInt(3, items.size());
                    statement.setBigDecimal(4, totalGross);
                    statement.setBigDecimal(5, totalDeductions);
                    statement.setBigDecimal(6, totalNet);
                    statement.setBigDecimal(7, totalEmployerCost);
                    statement.setObject(8, LocalDateTime.now());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        runId = keys.getLong(1);
                    }
                }
                insertDetails(connection, runId, items);
                insertFailures(connection, runId, failures);
                connection.commit();
                return findRun(runId)
                        .orElseThrow(() -> new IllegalStateException("批次写入后无法读取"));
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入工资批次失败：" + e.getMessage(), e);
        }
    }

    public Optional<PayrollRun> findRun(long runId) {
        String sql = "SELECT * FROM payroll_runs WHERE run_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询工资批次失败：" + e.getMessage(), e);
        }
    }

    public List<PayrollRun> runs() {
        String sql = "SELECT * FROM payroll_runs ORDER BY run_id DESC";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<PayrollRun> runs = new ArrayList<>();
            while (rs.next()) {
                runs.add(mapRun(rs));
            }
            return List.copyOf(runs);
        } catch (SQLException e) {
            throw new IllegalStateException("查询工资批次失败：" + e.getMessage(), e);
        }
    }

    public List<PayrollRunItem> items(long runId) {
        String sql = "SELECT * FROM payroll_details WHERE run_id = ? ORDER BY employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PayrollRunItem> items = new ArrayList<>();
                while (rs.next()) {
                    String adjustmentJson = rs.getString("adjustment_json");
                    items.add(
                            new PayrollRunItem(
                                    rs.getString("employee_id"),
                                    rs.getString("employee_name"),
                                    rs.getString("department"),
                                    rs.getString("city"),
                                    rs.getString("email"),
                                    adjustmentJson == null
                                            ? null
                                            : JsonSupport.fromJson(
                                                    adjustmentJson, PayrollAdjustment.class),
                                    JsonSupport.fromJson(
                                            rs.getString("result_json"), PayrollResult.class),
                                    rs.getString("email_status"),
                                    rs.getString("email_reason")));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询批次明细失败：" + e.getMessage(), e);
        }
    }

    public List<PayrollRunFailure> failures(long runId) {
        String sql =
                "SELECT employee_id, message FROM payroll_run_failures WHERE run_id = ?"
                        + " ORDER BY employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PayrollRunFailure> failures = new ArrayList<>();
                while (rs.next()) {
                    failures.add(
                            new PayrollRunFailure(rs.getString(1), rs.getString(2)));
                }
                return List.copyOf(failures);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询批次失败清单失败：" + e.getMessage(), e);
        }
    }

    public boolean markFinalized(long runId, String excelPath) {
        String sql =
                """
                UPDATE payroll_runs SET status = 'FINALIZED', excel_path = ?, finalized_at = ?
                WHERE run_id = ? AND status = 'APPROVED'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, excelPath);
            statement.setObject(2, LocalDateTime.now());
            statement.setLong(3, runId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("更新批次状态失败：" + e.getMessage(), e);
        }
    }

    public void updateResult(long runId, PayrollResult result) {
        String sql =
                "UPDATE payroll_details SET result_json = ? WHERE run_id = ? AND employee_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, JsonSupport.toJson(result));
            statement.setLong(2, runId);
            statement.setString(3, result.employeeId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新批次明细失败：" + e.getMessage(), e);
        }
    }

    public void updateExcelPath(long runId, String excelPath) {
        String sql = "UPDATE payroll_runs SET excel_path = ? WHERE run_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, excelPath);
            statement.setLong(2, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新 Excel 路径失败：" + e.getMessage(), e);
        }
    }

    public void markStatus(long runId, String status) {
        String sql = "UPDATE payroll_runs SET status = ? WHERE run_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新批次状态失败：" + e.getMessage(), e);
        }
    }

    public void recordEmailResults(long runId, List<PayrollEmailResult> results) {
        String detailSql =
                """
                UPDATE payroll_details SET email_status = ?, email_reason = ?
                WHERE run_id = ? AND employee_id = ?
                """;
        String auditSql =
                """
                INSERT INTO email_deliveries (run_id, employee_id, email, status, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement detail = connection.prepareStatement(detailSql);
                    PreparedStatement audit = connection.prepareStatement(auditSql)) {
                LocalDateTime now = LocalDateTime.now();
                for (PayrollEmailResult result : results) {
                    detail.setString(1, result.status());
                    if (result.reason() == null) {
                        detail.setNull(2, Types.VARCHAR);
                    } else {
                        detail.setString(2, result.reason());
                    }
                    detail.setLong(3, runId);
                    detail.setString(4, result.employeeId());
                    detail.addBatch();

                    audit.setLong(1, runId);
                    audit.setString(2, result.employeeId());
                    audit.setString(3, result.email());
                    audit.setString(4, result.status());
                    audit.setString(5, result.reason());
                    audit.setObject(6, now);
                    audit.addBatch();
                }
                detail.executeBatch();
                audit.executeBatch();
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("记录邮件发送结果失败：" + e.getMessage(), e);
        }
    }

    private static void insertDetails(
            Connection connection, long runId, List<PayrollRunItem> items) throws SQLException {
        String sql =
                """
                INSERT INTO payroll_details (run_id, employee_id, employee_name, department, city,
                    email, adjustment_json, result_json, email_status, email_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PayrollRunItem item : items) {
                statement.setLong(1, runId);
                statement.setString(2, item.employeeId());
                statement.setString(3, item.employeeName());
                statement.setString(4, item.department());
                statement.setString(5, item.city());
                statement.setString(6, item.email());
                if (item.adjustment() == null || item.adjustment().isEmpty()) {
                    statement.setNull(7, Types.CLOB);
                } else {
                    statement.setString(7, JsonSupport.toJson(item.adjustment()));
                }
                statement.setString(8, JsonSupport.toJson(item.payroll()));
                statement.setString(9, item.emailStatus());
                statement.setString(10, item.emailReason());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertFailures(
            Connection connection, long runId, List<PayrollRunFailure> failures)
            throws SQLException {
        if (failures.isEmpty()) {
            return;
        }
        String sql =
                "INSERT INTO payroll_run_failures (run_id, employee_id, message) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PayrollRunFailure failure : failures) {
                statement.setLong(1, runId);
                statement.setString(2, failure.employeeId());
                statement.setString(3, failure.message());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static BigDecimal sum(
            List<PayrollRunItem> items,
            java.util.function.Function<PayrollResult, BigDecimal> extractor) {
        BigDecimal total = BigDecimal.ZERO;
        for (PayrollRunItem item : items) {
            total = total.add(extractor.apply(item.payroll()));
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static PayrollRun mapRun(ResultSet rs) throws SQLException {
        return new PayrollRun(
                rs.getLong("run_id"),
                rs.getString("period"),
                rs.getString("scope"),
                rs.getString("status"),
                rs.getInt("employee_count"),
                rs.getBigDecimal("total_gross"),
                rs.getBigDecimal("total_deductions"),
                rs.getBigDecimal("total_net"),
                rs.getBigDecimal("total_employer_cost"),
                rs.getString("excel_path"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finalized_at", LocalDateTime.class),
                new PayrollApproval(
                        nullableLong(rs, "approval_flow_id"),
                        rs.getString("approval_flow_name"),
                        nullableLong(rs, "submitted_by_id"),
                        rs.getString("submitted_by_name"),
                        rs.getObject("submitted_at", LocalDateTime.class),
                        rs.getString("submit_comment"),
                        nullableLong(rs, "approved_by_id"),
                        rs.getString("approved_by_name"),
                        rs.getObject("approved_at", LocalDateTime.class),
                        rs.getString("approval_comment"),
                        nullableLong(rs, "rejected_by_id"),
                        rs.getString("rejected_by_name"),
                        rs.getObject("rejected_at", LocalDateTime.class),
                        rs.getString("rejection_reason"),
                        rs.getString("snapshot_hash"),
                        H2ApprovalStore.parseSteps(rs.getString("workflow_steps_json")),
                        rs.getInt("current_step")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
