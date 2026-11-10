package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** kb_document 的 JDBC 存储。 */
@Repository
public class KbDocumentStore {

    private static final String SELECT =
            "SELECT document_id, series_id, directory_id, name, ext, mime_type, size_bytes, sha256,"
                    + " storage_key, page_count, char_count, status, error_code, error_message,"
                    + " chunk_count, config_version_id, uploaded_by, row_version, created_at,"
                    + " updated_at, deleted_at FROM kb_document";

    private final PayrollDatabase database;

    public KbDocumentStore(PayrollDatabase database) {
        this.database = database;
    }

    public Optional<Document> findById(long documentId, long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE document_id = ? AND series_id = ?")) {
            statement.setLong(1, documentId);
            statement.setLong(2, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public Optional<Document> findById(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE document_id = ?")) {
            statement.setLong(1, documentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public List<Document> findByIds(long seriesId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql =
                SELECT
                        + " WHERE series_id = ? AND deleted_at IS NULL AND document_id IN ("
                        + placeholders
                        + ") ORDER BY updated_at DESC";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seriesId);
            int index = 2;
            for (Long id : ids) {
                statement.setLong(index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<Document> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public List<Document> findBySeries(
            long seriesId, Long directoryId, DocStatus status, String keyword) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE series_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(seriesId);
        if (directoryId != null) {
            sql.append(" AND directory_id = ?");
            params.add(directoryId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND LOWER(name) LIKE ?");
            params.add("%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        sql.append(" ORDER BY updated_at DESC, document_id DESC");
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<Document> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public List<Document> findReadyBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND status = 'READY' AND deleted_at IS NULL")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Document> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public Optional<Document> findBySha(long seriesId, String sha256) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND sha256 = ? AND deleted_at IS NULL"
                                        + " ORDER BY document_id LIMIT 1")) {
            statement.setLong(1, seriesId);
            statement.setString(2, sha256);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public List<Document> findByDirectory(long seriesId, long directoryId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND directory_id = ? AND deleted_at IS NULL")) {
            statement.setLong(1, seriesId);
            statement.setLong(2, directoryId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Document> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败：" + e.getMessage(), e);
        }
    }

    public Document insert(Document draft) {
        String sql =
                """
                INSERT INTO kb_document
                    (series_id, directory_id, name, ext, mime_type, size_bytes, sha256, storage_key,
                     status, chunk_count, config_version_id, uploaded_by, row_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 1, ?, ?)
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, draft.seriesId());
            statement.setLong(2, draft.directoryId());
            statement.setString(3, draft.name());
            statement.setString(4, draft.ext());
            statement.setString(5, draft.mimeType());
            statement.setLong(6, draft.sizeBytes());
            statement.setString(7, draft.sha256());
            statement.setString(8, draft.storageKey());
            statement.setString(9, draft.status().name());
            if (draft.configVersionId() == null) {
                statement.setNull(10, java.sql.Types.BIGINT);
            } else {
                statement.setLong(10, draft.configVersionId());
            }
            statement.setLong(11, draft.uploadedBy());
            statement.setTimestamp(12, now);
            statement.setTimestamp(13, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存文档失败：" + e.getMessage(), e);
        }
    }

    public void updateStatus(long documentId, DocStatus status, String errorCode, String errorMessage) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET status = ?, error_code = ?, error_message = ?,"
                                        + " updated_at = ? WHERE document_id = ?")) {
            statement.setString(1, status.name());
            if (errorCode == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, errorCode);
            }
            if (errorMessage == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, truncate(errorMessage, 500));
            }
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.setLong(5, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新文档状态失败：" + e.getMessage(), e);
        }
    }

    public void updateResult(
            long documentId,
            Integer pageCount,
            Long charCount,
            int chunkCount,
            Long configVersionId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET page_count = ?, char_count = ?, chunk_count = ?,"
                                        + " config_version_id = ?, updated_at = ? WHERE document_id = ?")) {
            if (pageCount == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setInt(1, pageCount);
            }
            if (charCount == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, charCount);
            }
            statement.setInt(3, chunkCount);
            if (configVersionId == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, configVersionId);
            }
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.setLong(6, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新文档解析结果失败：" + e.getMessage(), e);
        }
    }

    public void updateName(long documentId, String name) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET name = ?, updated_at = ?,"
                                        + " row_version = row_version + 1 WHERE document_id = ?")) {
            statement.setString(1, name);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("重命名文档失败：" + e.getMessage(), e);
        }
    }

    public void move(long documentId, long directoryId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET directory_id = ?, updated_at = ?,"
                                        + " row_version = row_version + 1 WHERE document_id = ?")) {
            statement.setLong(1, directoryId);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("移动文档失败：" + e.getMessage(), e);
        }
    }

    public void softDelete(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET status = 'DELETED', deleted_at = ?,"
                                        + " updated_at = ?, row_version = row_version + 1"
                                        + " WHERE document_id = ?")) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setLong(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除文档失败：" + e.getMessage(), e);
        }
    }

    public int[] countStatus(long seriesId) {
        int[] counters = new int[3];
        String sql =
                """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN status = 'READY' THEN 1 ELSE 0 END) AS ready,
                       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM kb_document WHERE series_id = ? AND deleted_at IS NULL
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    counters[0] = rs.getInt("total");
                    counters[1] = rs.getInt("ready");
                    counters[2] = rs.getInt("failed");
                }
                return counters;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计文档失败：" + e.getMessage(), e);
        }
    }

    public long sumChunkCount(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COALESCE(SUM(chunk_count), 0) FROM kb_document"
                                        + " WHERE series_id = ? AND deleted_at IS NULL")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计文档分块失败：" + e.getMessage(), e);
        }
    }

    public void clearStorageKey(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_document SET storage_key = '', updated_at = ?"
                                        + " WHERE document_id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setLong(2, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理文档存储引用失败：" + e.getMessage(), e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Document map(ResultSet rs) throws SQLException {
        return new Document(
                rs.getLong("document_id"),
                rs.getLong("series_id"),
                rs.getLong("directory_id"),
                rs.getString("name"),
                rs.getString("ext"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("storage_key"),
                KbSeriesStore.intOrNull(rs, "page_count"),
                KbSeriesStore.longOrNull(rs, "char_count"),
                DocStatus.parse(rs.getString("status"), DocStatus.PENDING),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getInt("chunk_count"),
                KbSeriesStore.longOrNull(rs, "config_version_id"),
                rs.getLong("uploaded_by"),
                rs.getLong("row_version"),
                KbSeriesStore.instant(rs, "created_at"),
                KbSeriesStore.instant(rs, "updated_at"),
                KbSeriesStore.instant(rs, "deleted_at"));
    }
}
