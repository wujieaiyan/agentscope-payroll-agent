package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code skill_data_state} 的库实现。表由 V24 建，只有两列时间戳有语义：
 * 沙箱给出的 {@code started_at/finished_at} 与本地观测时间 {@code observed_at}，
 * 后者决定后台刷新器多久之后再问一次沙箱。
 */
public final class JdbcSkillDataStateStore implements SkillDataStateStore {

    private static final String COLUMNS =
            "skill_id, status, size_bytes, exit_code, started_at, finished_at, message, observed_at";
    private static final String SELECT_ONE =
            "SELECT " + COLUMNS + " FROM skill_data_state WHERE skill_id = ?";
    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM skill_data_state";
    private static final String UPSERT =
            """
            INSERT INTO skill_data_state
                (skill_id, status, size_bytes, exit_code, started_at, finished_at, message, observed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                status = VALUES(status), size_bytes = VALUES(size_bytes), exit_code = VALUES(exit_code),
                started_at = VALUES(started_at), finished_at = VALUES(finished_at),
                message = VALUES(message), observed_at = VALUES(observed_at)
            """;
    private static final String DELETE = "DELETE FROM skill_data_state WHERE skill_id = ?";
    private static final String SELECT_PENDING =
            """
            SELECT %s FROM skill_data_state
            WHERE status IN ('INITIALIZING', 'BUSY') AND observed_at < ?
            ORDER BY observed_at ASC LIMIT ?
            """
                    .formatted(COLUMNS);

    private final PayrollDatabase database;

    public JdbcSkillDataStateStore(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public Optional<SkillDataState> find(String skillId) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
            statement.setString(1, SkillIds.normalize(skillId));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw unavailable("读取技能数据状态失败", e);
        }
    }

    @Override
    public Map<String, SkillDataState> findAll() {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                ResultSet rs = statement.executeQuery()) {
            Map<String, SkillDataState> states = new LinkedHashMap<>();
            while (rs.next()) {
                SkillDataState state = read(rs);
                states.put(state.skillId(), state);
            }
            return states;
        } catch (SQLException e) {
            throw unavailable("读取技能数据状态列表失败", e);
        }
    }

    @Override
    public void upsert(SkillDataState state) {
        if (state == null) {
            return;
        }
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, SkillIds.normalize(state.skillId()));
            statement.setString(2, state.status() == null ? SkillDataState.UNKNOWN : state.status());
            statement.setLong(3, state.sizeBytes());
            if (state.exitCode() == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, state.exitCode());
            }
            statement.setLong(5, state.startedAt());
            statement.setLong(6, state.finishedAt());
            statement.setString(7, state.message() == null ? "" : state.message());
            statement.setTimestamp(8, new Timestamp(state.observedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw unavailable("写入技能数据状态失败", e);
        }
    }

    @Override
    public void delete(String skillId) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, SkillIds.normalize(skillId));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw unavailable("删除技能数据状态失败", e);
        }
    }

    @Override
    public List<SkillDataState> pendingBefore(long cutoffEpochMillis, int limit) {
        int capped = limit < 1 ? 1 : Math.min(limit, 100);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(SELECT_PENDING)) {
            statement.setTimestamp(1, new Timestamp(cutoffEpochMillis));
            statement.setInt(2, capped);
            try (ResultSet rs = statement.executeQuery()) {
                List<SkillDataState> states = new ArrayList<>();
                while (rs.next()) {
                    states.add(read(rs));
                }
                return states;
            }
        } catch (SQLException e) {
            throw unavailable("读取待刷新的技能数据状态失败", e);
        }
    }

    private static SkillDataState read(ResultSet rs) throws SQLException {
        Timestamp observed = rs.getTimestamp("observed_at");
        int exit = rs.getInt("exit_code");
        Integer exitCode = rs.wasNull() ? null : exit;
        return new SkillDataState(
                rs.getString("skill_id"),
                rs.getString("status"),
                rs.getLong("size_bytes"),
                exitCode,
                rs.getLong("started_at"),
                rs.getLong("finished_at"),
                rs.getString("message") == null ? "" : rs.getString("message"),
                observed == null ? 0L : observed.getTime());
    }

    private static PayrollUnavailableException unavailable(String action, SQLException cause) {
        return new PayrollUnavailableException(action + "：" + cause.getMessage(), cause);
    }
}
