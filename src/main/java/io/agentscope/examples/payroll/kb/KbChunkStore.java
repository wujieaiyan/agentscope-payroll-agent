package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
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

/** kb_chunk 的 JDBC 存储；向量以 JSON 文本存放，便于与 ES 索引互为回退。 */
@Repository
public class KbChunkStore {

    private static final String SELECT =
            "SELECT chunk_id, series_id, document_id, parent_chunk_id, chunk_level, seq, content,"
                    + " content_hash, token_count, char_start, char_end, page_no, bbox, title_path,"
                    + " metadata, vector, vector_source, status, created_at FROM kb_chunk";

    private final PayrollDatabase database;

    public KbChunkStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Chunk> findByDocument(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE document_id = ? ORDER BY seq, chunk_id")) {
            statement.setLong(1, documentId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Chunk> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询分块失败：" + e.getMessage(), e);
        }
    }

    public List<Chunk> findActiveBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND status = 'ACTIVE' AND chunk_level <> 'PARENT'"
                                        + " ORDER BY document_id, seq")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Chunk> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询分块失败：" + e.getMessage(), e);
        }
    }

    public List<Chunk> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE chunk_id IN (" + placeholders + ")")) {
            int index = 1;
            for (Long id : ids) {
                statement.setLong(index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<Chunk> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询分块失败：" + e.getMessage(), e);
        }
    }

    public Optional<Chunk> findById(long chunkId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE chunk_id = ?")) {
            statement.setLong(1, chunkId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询分块失败：" + e.getMessage(), e);
        }
    }

    public List<Chunk> findChildren(long parentChunkId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE parent_chunk_id = ? ORDER BY seq, chunk_id")) {
            statement.setLong(1, parentChunkId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Chunk> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询分块失败：" + e.getMessage(), e);
        }
    }

    public long countByDocument(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM kb_chunk WHERE document_id = ?")) {
            statement.setLong(1, documentId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计分块失败：" + e.getMessage(), e);
        }
    }

    public long countBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM kb_chunk WHERE series_id = ? AND chunk_level <> 'PARENT'")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计分块失败：" + e.getMessage(), e);
        }
    }

    public void deleteByDocument(long documentId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_chunk WHERE document_id = ?")) {
            statement.setLong(1, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除分块失败：" + e.getMessage(), e);
        }
    }

    public void deleteBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_chunk WHERE series_id = ?")) {
            statement.setLong(1, seriesId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除分块失败：" + e.getMessage(), e);
        }
    }

    public void updateVector(long chunkId, String vector, String vectorSource) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_chunk SET vector = ?, vector_source = ? WHERE chunk_id = ?")) {
            statement.setString(1, vector);
            statement.setString(2, vectorSource);
            statement.setLong(3, chunkId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新分块向量失败：" + e.getMessage(), e);
        }
    }

    public void updateStatusByDocument(long documentId, String status) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_chunk SET status = ? WHERE document_id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新分块状态失败：" + e.getMessage(), e);
        }
    }

    public void insertBatch(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String sql =
                """
                INSERT INTO kb_chunk
                    (series_id, document_id, parent_chunk_id, chunk_level, seq, content, content_hash,
                     token_count, char_start, char_end, page_no, bbox, title_path, metadata, vector,
                     vector_source, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Chunk chunk : chunks) {
                statement.setLong(1, chunk.seriesId());
                statement.setLong(2, chunk.documentId());
                if (chunk.parentChunkId() == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, chunk.parentChunkId());
                }
                statement.setString(4, chunk.level().name());
                statement.setInt(5, chunk.seq());
                statement.setString(6, chunk.content());
                statement.setString(7, chunk.contentHash());
                statement.setInt(8, chunk.tokenCount());
                setInteger(statement, 9, chunk.charStart());
                setInteger(statement, 10, chunk.charEnd());
                setInteger(statement, 11, chunk.pageNo());
                setString(statement, 12, chunk.bbox());
                setString(statement, 13, chunk.titlePath());
                setString(statement, 14, chunk.metadata());
                setString(statement, 15, chunk.vector());
                statement.setString(16, chunk.vectorSource() == null ? "HASH" : chunk.vectorSource());
                statement.setString(17, chunk.status() == null ? "ACTIVE" : chunk.status());
                statement.setTimestamp(18, Timestamp.from(chunk.createdAt() == null ? now.toInstant() : chunk.createdAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("保存分块失败：" + e.getMessage(), e);
        }
    }

    public long insert(Chunk chunk) {
        String sql =
                """
                INSERT INTO kb_chunk
                    (series_id, document_id, parent_chunk_id, chunk_level, seq, content, content_hash,
                     token_count, char_start, char_end, page_no, bbox, title_path, metadata, vector,
                     vector_source, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, chunk.seriesId());
            statement.setLong(2, chunk.documentId());
            if (chunk.parentChunkId() == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, chunk.parentChunkId());
            }
            statement.setString(4, chunk.level().name());
            statement.setInt(5, chunk.seq());
            statement.setString(6, chunk.content());
            statement.setString(7, chunk.contentHash());
            statement.setInt(8, chunk.tokenCount());
            setInteger(statement, 9, chunk.charStart());
            setInteger(statement, 10, chunk.charEnd());
            setInteger(statement, 11, chunk.pageNo());
            setString(statement, 12, chunk.bbox());
            setString(statement, 13, chunk.titlePath());
            setString(statement, 14, chunk.metadata());
            setString(statement, 15, chunk.vector());
            statement.setString(16, chunk.vectorSource() == null ? "HASH" : chunk.vectorSource());
            statement.setString(17, chunk.status() == null ? "ACTIVE" : chunk.status());
            statement.setTimestamp(
                    18,
                    Timestamp.from(
                            chunk.createdAt() == null ? Instant.now() : chunk.createdAt()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存分块失败：" + e.getMessage(), e);
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private Chunk map(ResultSet rs) throws SQLException {
        return new Chunk(
                rs.getLong("chunk_id"),
                rs.getLong("series_id"),
                rs.getLong("document_id"),
                KbSeriesStore.longOrNull(rs, "parent_chunk_id"),
                ChunkLevel.parse(rs.getString("chunk_level"), ChunkLevel.SINGLE),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getString("content_hash"),
                rs.getInt("token_count"),
                KbSeriesStore.intOrNull(rs, "char_start"),
                KbSeriesStore.intOrNull(rs, "char_end"),
                KbSeriesStore.intOrNull(rs, "page_no"),
                rs.getString("bbox"),
                rs.getString("title_path"),
                rs.getString("metadata"),
                rs.getString("vector"),
                rs.getString("vector_source"),
                rs.getString("status"),
                KbSeriesStore.instant(rs, "created_at"));
    }
}
