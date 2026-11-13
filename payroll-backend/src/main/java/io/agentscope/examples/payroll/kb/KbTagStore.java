package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbModels.Tag;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** kb_tag 与 kb_document_tag 的 JDBC 存储。 */
@Repository
public class KbTagStore {

    private static final String SELECT =
            "SELECT tag_id, series_id, name, color, created_by, created_at FROM kb_tag";

    private final PayrollDatabase database;

    public KbTagStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Tag> findBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE series_id = ? ORDER BY name")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Tag> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询标签失败：" + e.getMessage(), e);
        }
    }

    public Optional<Tag> findById(long tagId, long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE tag_id = ? AND series_id = ?")) {
            statement.setLong(1, tagId);
            statement.setLong(2, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询标签失败：" + e.getMessage(), e);
        }
    }

    public Optional<Tag> findByName(long seriesId, String name) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE series_id = ? AND name = ?")) {
            statement.setLong(1, seriesId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询标签失败：" + e.getMessage(), e);
        }
    }

    public Tag insert(long seriesId, String name, String color, long createdBy) {
        String sql =
                "INSERT INTO kb_tag (series_id, name, color, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, seriesId);
            statement.setString(2, name);
            statement.setString(3, color);
            statement.setLong(4, createdBy);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1), seriesId).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建标签失败：" + e.getMessage(), e);
        }
    }

    public void update(long tagId, String name, String color) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_tag SET name = ?, color = ? WHERE tag_id = ?")) {
            statement.setString(1, name);
            statement.setString(2, color);
            statement.setLong(3, tagId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新标签失败：" + e.getMessage(), e);
        }
    }

    public void delete(long tagId) {
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_document_tag WHERE tag_id = ?")) {
                statement.setLong(1, tagId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_tag WHERE tag_id = ?")) {
                statement.setLong(1, tagId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除标签失败：" + e.getMessage(), e);
        }
    }

    public void replaceDocumentTags(long seriesId, long documentId, List<Long> tagIds) {
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_document_tag WHERE document_id = ?")) {
                statement.setLong(1, documentId);
                statement.executeUpdate();
            }
            if (tagIds == null || tagIds.isEmpty()) {
                return;
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO kb_document_tag (document_id, tag_id, series_id) VALUES (?, ?, ?)")) {
                for (Long tagId : tagIds) {
                    statement.setLong(1, documentId);
                    statement.setLong(2, tagId);
                    statement.setLong(3, seriesId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存文档标签失败：" + e.getMessage(), e);
        }
    }

    public void deleteDocumentTags(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_document_tag WHERE document_id = ?")) {
            statement.setLong(1, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理文档标签失败：" + e.getMessage(), e);
        }
    }

    public void deleteSeriesTags(long seriesId) {
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_document_tag WHERE series_id = ?")) {
                statement.setLong(1, seriesId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM kb_tag WHERE series_id = ?")) {
                statement.setLong(1, seriesId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清理知识库标签失败：" + e.getMessage(), e);
        }
    }

    public Map<Long, List<Tag>> findByDocuments(Collection<Long> documentIds) {
        Map<Long, List<Tag>> result = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql =
                "SELECT dt.document_id, t.tag_id, t.series_id, t.name, t.color, t.created_by, t.created_at"
                        + " FROM kb_document_tag dt JOIN kb_tag t ON t.tag_id = dt.tag_id"
                        + " WHERE dt.document_id IN ("
                        + placeholders
                        + ") ORDER BY t.name";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Long id : documentIds) {
                statement.setLong(index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long documentId = rs.getLong("document_id");
                    result.computeIfAbsent(documentId, key -> new ArrayList<>()).add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档标签失败：" + e.getMessage(), e);
        }
    }

    public Map<Long, Integer> countDocumentsByTag(long seriesId) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        String sql =
                "SELECT dt.tag_id, COUNT(*) AS total FROM kb_document_tag dt"
                        + " JOIN kb_document d ON d.document_id = dt.document_id"
                        + " WHERE dt.series_id = ? AND d.deleted_at IS NULL GROUP BY dt.tag_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong("tag_id"), rs.getInt("total"));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("统计标签失败：" + e.getMessage(), e);
        }
    }

    private Tag map(ResultSet rs) throws SQLException {
        return new Tag(
                rs.getLong("tag_id"),
                rs.getLong("series_id"),
                rs.getString("name"),
                rs.getString("color"),
                rs.getLong("created_by"),
                KbSeriesStore.instant(rs, "created_at"));
    }
}
