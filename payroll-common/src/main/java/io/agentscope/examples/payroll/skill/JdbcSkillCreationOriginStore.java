package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code skill_creation_origin} 的库实现（V24）。 */
public final class JdbcSkillCreationOriginStore implements SkillCreationOriginStore {

    private static final String UPSERT =
            """
            INSERT INTO skill_creation_origin (skill_id, created_from, created_by)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE created_from = VALUES(created_from),
                                    created_by = VALUES(created_by),
                                    created_at = CURRENT_TIMESTAMP
            """;
    private static final String SELECT_ALL =
            "SELECT skill_id, created_from, created_by, created_at FROM skill_creation_origin";
    private static final String DELETE = "DELETE FROM skill_creation_origin WHERE skill_id = ?";

    private final PayrollDatabase database;

    public JdbcSkillCreationOriginStore(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public void record(String skillId, String templateId, String operator) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, SkillIds.normalize(skillId));
            statement.setString(2, templateId == null ? "" : templateId);
            statement.setString(3, operator == null || operator.isBlank() ? null : operator);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw unavailable("记录技能创建来源失败", e);
        }
    }

    @Override
    public Map<String, Origin> origins() {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                ResultSet rs = statement.executeQuery()) {
            Map<String, Origin> origins = new LinkedHashMap<>();
            while (rs.next()) {
                Timestamp createdAt = rs.getTimestamp("created_at");
                origins.put(
                        rs.getString("skill_id"),
                        new Origin(
                                rs.getString("created_from"),
                                rs.getString("created_by") == null ? "" : rs.getString("created_by"),
                                createdAt == null ? 0L : createdAt.getTime()));
            }
            return origins;
        } catch (SQLException e) {
            throw unavailable("读取技能创建来源失败", e);
        }
    }

    @Override
    public void delete(String skillId) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, SkillIds.normalize(skillId));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw unavailable("删除技能创建来源失败", e);
        }
    }

    private static PayrollUnavailableException unavailable(String action, SQLException cause) {
        return new PayrollUnavailableException(action + "：" + cause.getMessage(), cause);
    }
}
