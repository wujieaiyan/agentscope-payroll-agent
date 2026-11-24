package io.agentscope.examples.payroll.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 启动任务与周期任务的数据库租约（{@code job_lease}）。
 *
 * <p>所有时间表达式都在 SQL 内取数据库时钟，Java 只传 TTL 秒数，避免各节点 JVM 时钟偏移
 * 影响互斥窗口；方言差异（H2 {@code DATEADD} / MySQL {@code TIMESTAMPADD}）只在本类处理。
 */
public final class JobLeaseStore {

    private final PayrollDatabase database;

    public JobLeaseStore(PayrollDatabase database) {
        this.database = database;
    }

    public record LeaseRow(
            String jobName, String owner, LocalDateTime acquiredAt, LocalDateTime expiresAt) {}

    /** 申请：过期行直接接管；无行则插入。主键冲突视为未抢到。 */
    public boolean tryAcquire(String jobName, String owner, int ttlSeconds) {
        String update =
                "UPDATE job_lease SET owner=?, acquired_at=CURRENT_TIMESTAMP, expires_at="
                        + addSeconds("?")
                        + " WHERE job_name=? AND expires_at < CURRENT_TIMESTAMP";
        String insert =
                "INSERT INTO job_lease (job_name, owner, acquired_at, expires_at) VALUES (?, ?,"
                        + " CURRENT_TIMESTAMP, "
                        + addSeconds("?")
                        + ")";
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, owner);
                statement.setInt(2, Math.max(1, ttlSeconds));
                statement.setString(3, jobName);
                if (statement.executeUpdate() > 0) {
                    return true;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, jobName);
                statement.setString(2, owner);
                statement.setInt(3, Math.max(1, ttlSeconds));
                statement.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isConstraintViolation(e)) {
                    return false;
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("申请任务租约失败：" + e.getMessage(), e);
        }
    }

    /** 续租；影响行数为 0 表示租约已被其他节点接管。 */
    public boolean renew(String jobName, String owner, int ttlSeconds) {
        String sql =
                "UPDATE job_lease SET expires_at="
                        + addSeconds("?")
                        + " WHERE job_name=? AND owner=?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, ttlSeconds));
            statement.setString(2, jobName);
            statement.setString(3, owner);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("续租任务租约失败：" + e.getMessage(), e);
        }
    }

    /** 释放：置为过去的过期时间，立即可被接管，同时保留行便于排查。 */
    public void release(String jobName, String owner) {
        String sql =
                "UPDATE job_lease SET expires_at="
                        + addSeconds("-1")
                        + " WHERE job_name=? AND owner=?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobName);
            statement.setString(2, owner);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("释放任务租约失败：" + e.getMessage(), e);
        }
    }

    public Optional<LeaseRow> find(String jobName) {
        String sql =
                "SELECT job_name, owner, acquired_at, expires_at FROM job_lease WHERE job_name=?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new LeaseRow(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getObject(3, LocalDateTime.class),
                                rows.getObject(4, LocalDateTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取任务租约失败：" + e.getMessage(), e);
        }
    }

    private String addSeconds(String ttlPlaceholder) {
        return database.dialect(
                "DATEADD('SECOND', " + ttlPlaceholder + ", CURRENT_TIMESTAMP)",
                "TIMESTAMPADD(SECOND, " + ttlPlaceholder + ", CURRENT_TIMESTAMP)");
    }

    private static boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
