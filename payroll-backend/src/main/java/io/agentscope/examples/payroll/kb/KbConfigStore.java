package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** kb_config_version 的 JDBC 存储。 */
@Repository
public class KbConfigStore {

    private static final String SELECT =
            "SELECT version_id, series_id, version_no, index_strategy, parse_config, chunk_config,"
                    + " retrieval_config, change_note, created_by, created_at FROM kb_config_version";

    private final PayrollDatabase database;

    public KbConfigStore(PayrollDatabase database) {
        this.database = database;
    }

    public ConfigVersion insert(
            long seriesId,
            int versionNo,
            IndexStrategy strategy,
            String parse,
            String chunk,
            String retrieval,
            String note,
            long createdBy) {
        String sql =
                """
                INSERT INTO kb_config_version
                    (series_id, version_no, index_strategy, parse_config, chunk_config,
                     retrieval_config, change_note, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, seriesId);
            statement.setInt(2, versionNo);
            statement.setString(3, strategy.name());
            statement.setString(4, parse);
            statement.setString(5, chunk);
            statement.setString(6, retrieval);
            statement.setString(7, note == null ? "" : note);
            statement.setLong(8, createdBy);
            statement.setTimestamp(9, Timestamp.from(Instant.now()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存知识库配置失败：" + e.getMessage(), e);
        }
    }

    public Optional<ConfigVersion> findById(long id) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE version_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库配置失败：" + e.getMessage(), e);
        }
    }

    public Optional<ConfigVersion> findByIdAndSeries(long id, long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE version_id = ? AND series_id = ?")) {
            statement.setLong(1, id);
            statement.setLong(2, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库配置失败：" + e.getMessage(), e);
        }
    }

    public Optional<ConfigVersion> findLatest(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? ORDER BY version_no DESC LIMIT 1")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库配置失败：" + e.getMessage(), e);
        }
    }

    public List<ConfigVersion> findBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? ORDER BY version_no DESC")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ConfigVersion> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库配置失败：" + e.getMessage(), e);
        }
    }

    public void deleteBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_config_version WHERE series_id = ?")) {
            statement.setLong(1, seriesId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理知识库配置失败：" + e.getMessage(), e);
        }
    }

    private ConfigVersion map(ResultSet rs) throws SQLException {
        return new ConfigVersion(
                rs.getLong("version_id"),
                rs.getLong("series_id"),
                rs.getInt("version_no"),
                IndexStrategy.parse(rs.getString("index_strategy"), IndexStrategy.HYBRID),
                rs.getString("parse_config"),
                rs.getString("chunk_config"),
                rs.getString("retrieval_config"),
                rs.getString("change_note"),
                rs.getLong("created_by"),
                KbSeriesStore.instant(rs, "created_at"));
    }
}
