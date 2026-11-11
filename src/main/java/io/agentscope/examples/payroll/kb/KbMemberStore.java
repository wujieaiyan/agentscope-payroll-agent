package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.kb.KbModels.Member;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** kb_member 的 JDBC 存储；用户与部门成员共用一张表。 */
@Repository
public class KbMemberStore {

    private static final String SELECT =
            "SELECT member_id, series_id, subject_type, subject_id, member_role, granted_by,"
                    + " created_at, updated_at FROM kb_member";

    private final PayrollDatabase database;

    public KbMemberStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Member> findBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE series_id = ? ORDER BY created_at, member_id")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Member> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库成员失败：" + e.getMessage(), e);
        }
    }

    public List<Member> findBySubject(SubjectType type, long subjectId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE subject_type = ? AND subject_id = ?")) {
            statement.setString(1, type.name());
            statement.setLong(2, subjectId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Member> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库成员失败：" + e.getMessage(), e);
        }
    }

    public Optional<Member> findOne(long seriesId, SubjectType type, long subjectId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND subject_type = ? AND subject_id = ?")) {
            statement.setLong(1, seriesId);
            statement.setString(2, type.name());
            statement.setLong(3, subjectId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库成员失败：" + e.getMessage(), e);
        }
    }

    public Member upsert(long seriesId, SubjectType type, long subjectId, KbRole role, long grantedBy) {
        Optional<Member> existing = findOne(seriesId, type, subjectId);
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = database.connection()) {
            if (existing.isPresent()) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_member SET member_role = ?, updated_at = ? WHERE member_id = ?")) {
                    statement.setString(1, role.name());
                    statement.setTimestamp(2, now);
                    statement.setLong(3, existing.get().id());
                    statement.executeUpdate();
                }
                return findOne(seriesId, type, subjectId).orElseThrow();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO kb_member (series_id, subject_type, subject_id, member_role,"
                                    + " granted_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, seriesId);
                statement.setString(2, type.name());
                statement.setLong(3, subjectId);
                statement.setString(4, role.name());
                statement.setLong(5, grantedBy);
                statement.setTimestamp(6, now);
                statement.setTimestamp(7, now);
                statement.executeUpdate();
            }
            return findOne(seriesId, type, subjectId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("保存知识库成员失败：" + e.getMessage(), e);
        }
    }

    public Optional<Member> findById(long memberId, long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE member_id = ? AND series_id = ?")) {
            statement.setLong(1, memberId);
            statement.setLong(2, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库成员失败：" + e.getMessage(), e);
        }
    }

    public void updateRole(long memberId, KbRole role) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_member SET member_role = ?, updated_at = ? WHERE member_id = ?")) {
            statement.setString(1, role.name());
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, memberId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新成员角色失败：" + e.getMessage(), e);
        }
    }

    public void delete(long memberId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_member WHERE member_id = ?")) {
            statement.setLong(1, memberId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("移除成员失败：" + e.getMessage(), e);
        }
    }

    public void deleteBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_member WHERE series_id = ?")) {
            statement.setLong(1, seriesId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理知识库成员失败：" + e.getMessage(), e);
        }
    }

    public long countAdmins(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM kb_member WHERE series_id = ? AND member_role IN ('OWNER','ADMIN')")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计管理员失败：" + e.getMessage(), e);
        }
    }

    public long countOwners(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM kb_member WHERE series_id = ? AND member_role = 'OWNER'")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 owner 失败：" + e.getMessage(), e);
        }
    }

    private Member map(ResultSet rs) throws SQLException {
        return new Member(
                rs.getLong("member_id"),
                rs.getLong("series_id"),
                SubjectType.parse(rs.getString("subject_type"), SubjectType.USER),
                rs.getLong("subject_id"),
                KbRole.parse(rs.getString("member_role"), KbRole.READER),
                rs.getLong("granted_by"),
                KbSeriesStore.instant(rs, "created_at"),
                KbSeriesStore.instant(rs, "updated_at"));
    }
}
