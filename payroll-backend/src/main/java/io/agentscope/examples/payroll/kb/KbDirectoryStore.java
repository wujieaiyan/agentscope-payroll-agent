package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.kb.KbModels.Directory;
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

/** kb_directory 的 JDBC 存储；目录路径以 /id/id/ 形式冗余存储便于前缀查询。 */
@Repository
public class KbDirectoryStore {

    private static final String SELECT =
            "SELECT directory_id, series_id, parent_id, name, dir_path, dir_depth, is_root,"
                    + " sort_order, created_by, created_at, updated_at FROM kb_directory";

    private final PayrollDatabase database;

    public KbDirectoryStore(PayrollDatabase database) {
        this.database = database;
    }

    public List<Directory> findBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? ORDER BY is_root DESC, dir_depth, sort_order, name")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Directory> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目录失败：" + e.getMessage(), e);
        }
    }

    public Optional<Directory> findById(long directoryId, long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE directory_id = ? AND series_id = ?")) {
            statement.setLong(1, directoryId);
            statement.setLong(2, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目录失败：" + e.getMessage(), e);
        }
    }

    public Optional<Directory> findRoot(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND is_root = TRUE LIMIT 1")) {
            statement.setLong(1, seriesId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目录失败：" + e.getMessage(), e);
        }
    }

    public List<Directory> findChildren(long seriesId, Long parentId) {
        String sql =
                parentId == null
                        ? SELECT + " WHERE series_id = ? AND parent_id IS NULL ORDER BY sort_order, name"
                        : SELECT + " WHERE series_id = ? AND parent_id = ? ORDER BY sort_order, name";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seriesId);
            if (parentId != null) {
                statement.setLong(2, parentId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<Directory> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目录失败：" + e.getMessage(), e);
        }
    }

    public List<Directory> findByPathPrefix(long seriesId, String pathPrefix) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT + " WHERE series_id = ? AND dir_path LIKE ?")) {
            statement.setLong(1, seriesId);
            statement.setString(2, pathPrefix + "%");
            try (ResultSet rs = statement.executeQuery()) {
                List<Directory> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目录失败：" + e.getMessage(), e);
        }
    }

    public boolean existsSibling(long seriesId, Long parentId, String name, Long excludeId) {
        String sql =
                "SELECT COUNT(*) FROM kb_directory WHERE series_id = ? AND name = ? AND"
                        + (parentId == null ? " parent_id IS NULL" : " parent_id = ?")
                        + (excludeId == null ? "" : " AND directory_id <> ?");
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, seriesId);
            statement.setString(index++, name);
            if (parentId != null) {
                statement.setLong(index++, parentId);
            }
            if (excludeId != null) {
                statement.setLong(index, excludeId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验目录名称失败：" + e.getMessage(), e);
        }
    }

    public Directory insert(
            long seriesId,
            Long parentId,
            String name,
            String path,
            int depth,
            boolean root,
            int sortOrder,
            long createdBy) {
        String sql =
                """
                INSERT INTO kb_directory
                    (series_id, parent_id, name, dir_path, dir_depth, is_root, sort_order,
                     created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, seriesId);
            if (parentId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, parentId);
            }
            statement.setString(3, name);
            statement.setString(4, path);
            statement.setInt(5, depth);
            statement.setBoolean(6, root);
            statement.setInt(7, sortOrder);
            statement.setLong(8, createdBy);
            statement.setTimestamp(9, now);
            statement.setTimestamp(10, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                return findById(id, seriesId).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建目录失败：" + e.getMessage(), e);
        }
    }

    public void rename(long directoryId, String name, int sortOrder) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_directory SET name = ?, sort_order = ?, updated_at = ?"
                                        + " WHERE directory_id = ?")) {
            statement.setString(1, name);
            statement.setInt(2, sortOrder);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setLong(4, directoryId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("重命名目录失败：" + e.getMessage(), e);
        }
    }

    public void move(long directoryId, Long parentId, String path, int depth, int sortOrder) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_directory SET parent_id = ?, dir_path = ?, dir_depth = ?,"
                                        + " sort_order = ?, updated_at = ? WHERE directory_id = ?")) {
            if (parentId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, parentId);
            }
            statement.setString(2, path);
            statement.setInt(3, depth);
            statement.setInt(4, sortOrder);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.setLong(6, directoryId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("移动目录失败：" + e.getMessage(), e);
        }
    }

    public void updatePath(long directoryId, String path, int depth) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE kb_directory SET dir_path = ?, dir_depth = ?, updated_at = ?"
                                        + " WHERE directory_id = ?")) {
            statement.setString(1, path);
            statement.setInt(2, depth);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setLong(4, directoryId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新目录路径失败：" + e.getMessage(), e);
        }
    }

    public void delete(long directoryId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_directory WHERE directory_id = ?")) {
            statement.setLong(1, directoryId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除目录失败：" + e.getMessage(), e);
        }
    }

    public void deleteBySeries(long seriesId) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM kb_directory WHERE series_id = ?")) {
            statement.setLong(1, seriesId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理目录失败：" + e.getMessage(), e);
        }
    }

    private Directory map(ResultSet rs) throws SQLException {
        return new Directory(
                rs.getLong("directory_id"),
                rs.getLong("series_id"),
                KbSeriesStore.longOrNull(rs, "parent_id"),
                rs.getString("name"),
                rs.getString("dir_path"),
                rs.getInt("dir_depth"),
                rs.getBoolean("is_root"),
                rs.getInt("sort_order"),
                rs.getLong("created_by"),
                KbSeriesStore.instant(rs, "created_at"),
                KbSeriesStore.instant(rs, "updated_at"));
    }
}
