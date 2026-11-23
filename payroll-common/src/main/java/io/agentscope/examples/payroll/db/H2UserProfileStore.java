package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.profile.UserProfileExtraction;
import io.agentscope.examples.payroll.profile.UserProfileItem;
import io.agentscope.examples.payroll.profile.UserProfileStore;
import io.agentscope.examples.payroll.profile.UserProfileTaxonomy;
import io.agentscope.examples.payroll.profile.UserProfileTurn;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 用户工作偏好的 H2/MySQL 读写：条目 CRUD 与认领式抽取凭证。 */
public final class H2UserProfileStore implements UserProfileStore {

    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofHours(1);
    private static final int MAX_ATTEMPTS = 3;

    private static final String COLUMNS =
            "item_id, user_id, category, item_key, item_value, confidence, source, "
                    + "source_turn_id, evidence, status, injected_count, last_injected_at, "
                    + "created_at, updated_at";
    private static final String ORDER =
            """
            ORDER BY CASE category
                        WHEN 'ROLE' THEN 0
                        WHEN 'SCOPE' THEN 1
                        WHEN 'PREFERENCE' THEN 2
                        WHEN 'TOPIC' THEN 3
                        ELSE 9
                    END,
                    updated_at DESC, item_key
            """;

    private final PayrollDatabase database;

    public H2UserProfileStore(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public List<UserProfileItem> items(long userId, boolean includeInactive) {
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM user_profile_items WHERE user_id = ?"
                        + (includeInactive ? "" : " AND status <> 'DISABLED'")
                        + " "
                        + ORDER;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UserProfileItem> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<UserProfileItem> active(long userId, int limit) {
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM user_profile_items WHERE user_id = ? AND status = 'ACTIVE' "
                        + ORDER
                        + " LIMIT ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                List<UserProfileItem> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> revision(long userId) {
        String sql =
                """
                SELECT MAX(updated_at) AS last_at, COUNT(*) AS total
                FROM user_profile_items
                WHERE user_id = ? AND status = 'ACTIVE'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || rs.getInt("total") == 0) {
                    return Optional.empty();
                }
                Timestamp last = rs.getTimestamp("last_at");
                long epoch = last == null ? 0L : last.toLocalDateTime().toEpochSecond(java.time.ZoneOffset.UTC);
                return Optional.of(sha256(userId + "|" + epoch + "|" + rs.getInt("total")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取工作偏好指纹失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<UserProfileItem> item(long itemId, long userId) {
        String sql =
                "SELECT " + COLUMNS + " FROM user_profile_items WHERE item_id = ? AND user_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, itemId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public UserProfileItem upsert(UserProfileItem item) {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                long id = upsert(connection, item, now);
                connection.commit();
                return itemById(connection, id);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean updateStatus(long itemId, long userId, String status) {
        String sql =
                "UPDATE user_profile_items SET status = ?, updated_at = ? "
                        + "WHERE item_id = ? AND user_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, LocalDateTime.now());
            statement.setLong(3, itemId);
            statement.setLong(4, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新工作偏好状态失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(long itemId, long userId) {
        String sql = "DELETE FROM user_profile_items WHERE item_id = ? AND user_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, itemId);
            statement.setLong(2, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int deleteAll(long userId) {
        String sql = "DELETE FROM user_profile_items WHERE user_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清空工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int purge(long userId) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                int items = deleteAll(connection, userId);
                int extractions;
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM user_profile_extractions WHERE user_id = ?")) {
                    statement.setLong(1, userId);
                    extractions = statement.executeUpdate();
                }
                connection.commit();
                return items + extractions;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("合规删除工作偏好失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int touchInjected(long userId, List<Long> itemIds, LocalDateTime now) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        String sql =
                """
                UPDATE user_profile_items
                SET injected_count = injected_count + 1, last_injected_at = ?
                WHERE user_id = ? AND item_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Long itemId : itemIds) {
                statement.setObject(1, now);
                statement.setLong(2, userId);
                statement.setLong(3, itemId);
                statement.addBatch();
            }
            int updated = 0;
            for (int count : statement.executeBatch()) {
                if (count > 0) {
                    updated += count;
                }
            }
            return updated;
        } catch (SQLException e) {
            throw new IllegalStateException("累计偏好注入次数失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<String> departments() {
        return distinct("SELECT name FROM departments WHERE active = TRUE ORDER BY name");
    }

    @Override
    public List<String> cities() {
        return distinct("SELECT city FROM city_policies ORDER BY city");
    }

    private List<String> distinct(String sql) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null && !value.isBlank()) {
                    rows.add(value);
                }
            }
            return List.copyOf(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("读取偏好词典失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<UserProfileTurn> window(long userId, int maxTurns) {
        Optional<Cursor> cursor = cursor(userId);
        String sql =
                """
                SELECT t.turn_id, t.user_id, t.started_at, m.content
                FROM chat_turns t
                INNER JOIN chat_messages m ON m.message_id = t.user_message_id
                WHERE t.user_id = ? AND t.status = 'SUCCEEDED' AND t.user_message_id > 0
                """
                        + (cursor.isPresent()
                                ? " AND (t.started_at > ? OR (t.started_at = ? AND t.turn_id > ?))"
                                : "")
                        + " ORDER BY t.started_at, t.turn_id LIMIT ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, userId);
            if (cursor.isPresent()) {
                Timestamp startedAt = Timestamp.valueOf(cursor.get().startedAt());
                statement.setTimestamp(index++, startedAt);
                statement.setTimestamp(index++, startedAt);
                statement.setString(index++, cursor.get().turnId());
            }
            statement.setInt(index, Math.max(1, maxTurns));
            try (ResultSet rs = statement.executeQuery()) {
                List<UserProfileTurn> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(
                            new UserProfileTurn(
                                    rs.getString("turn_id"),
                                    rs.getLong("user_id"),
                                    rs.getTimestamp("started_at").toLocalDateTime(),
                                    rs.getString("content")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取偏好抽取窗口失败：" + e.getMessage(), e);
        }
    }

    private Optional<Cursor> cursor(long userId) {
        String sql =
                """
                SELECT t.started_at, t.turn_id
                FROM chat_turns t
                WHERE t.turn_id =
                    (SELECT e.target_turn_id FROM user_profile_extractions e
                     WHERE e.user_id = ? AND e.status IN ('DONE', 'SKIPPED')
                     ORDER BY e.updated_at DESC, e.extraction_id DESC LIMIT 1)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new Cursor(
                                rs.getTimestamp("started_at").toLocalDateTime(),
                                rs.getString("turn_id")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取偏好抽取游标失败：" + e.getMessage(), e);
        }
    }

    private record Cursor(LocalDateTime startedAt, String turnId) {
    }

    @Override
    public Claim claim(long userId, String targetTurnId, LocalDateTime now) {
        String insert =
                """
                INSERT INTO user_profile_extractions
                    (user_id, target_turn_id, status, attempts, detail, created_at, updated_at)
                VALUES (?, ?, 'CLAIMED', 0, NULL, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setLong(1, userId);
            statement.setString(2, targetTurnId);
            statement.setObject(3, now);
            statement.setObject(4, now);
            statement.executeUpdate();
            return Claim.CLAIMED;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw new IllegalStateException("认领偏好抽取失败：" + e.getMessage(), e);
            }
        }
        return reclaim(userId, targetTurnId, now);
    }

    private Claim reclaim(long userId, String targetTurnId, LocalDateTime now) {
        String select =
                """
                SELECT status, attempts, updated_at FROM user_profile_extractions
                WHERE user_id = ? AND target_turn_id = ?
                """;
        try (Connection connection = database.connection()) {
            String status;
            int attempts;
            LocalDateTime updatedAt;
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setLong(1, userId);
                statement.setString(2, targetTurnId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Claim.DUPLICATE;
                    }
                    status = rs.getString("status");
                    attempts = rs.getInt("attempts");
                    updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
                }
            }
            if (UserProfileExtraction.STATUS_CLAIMED.equals(status)) {
                if (!updatedAt.isBefore(now.minus(LEASE))) {
                    return Claim.DUPLICATE;
                }
                return takeover(
                                connection,
                                userId,
                                targetTurnId,
                                now,
                                "status = 'CLAIMED' AND updated_at < ?",
                                Timestamp.valueOf(now.minus(LEASE)))
                        ? Claim.CLAIMED
                        : Claim.DUPLICATE;
            }
            if (UserProfileExtraction.STATUS_FAILED.equals(status)) {
                if (attempts >= MAX_ATTEMPTS) {
                    skip(connection, userId, targetTurnId, now);
                    return Claim.DUPLICATE;
                }
                if (!updatedAt.isBefore(now.minus(COOLDOWN))) {
                    return Claim.COOLDOWN;
                }
                return takeover(
                                connection,
                                userId,
                                targetTurnId,
                                now,
                                "status = 'FAILED' AND updated_at < ?",
                                Timestamp.valueOf(now.minus(COOLDOWN)))
                        ? Claim.CLAIMED
                        : Claim.DUPLICATE;
            }
            return Claim.DUPLICATE;
        } catch (SQLException e) {
            throw new IllegalStateException("检查偏好抽取凭证失败：" + e.getMessage(), e);
        }
    }

    private boolean takeover(
            Connection connection,
            long userId,
            String targetTurnId,
            LocalDateTime now,
            String guard,
            Timestamp guardValue)
            throws SQLException {
        String sql =
                "UPDATE user_profile_extractions SET status = 'CLAIMED', updated_at = ? "
                        + "WHERE user_id = ? AND target_turn_id = ? AND "
                        + guard;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.setLong(2, userId);
            statement.setString(3, targetTurnId);
            statement.setTimestamp(4, guardValue);
            return statement.executeUpdate() > 0;
        }
    }

    private void skip(Connection connection, long userId, String targetTurnId, LocalDateTime now)
            throws SQLException {
        String sql =
                """
                UPDATE user_profile_extractions
                SET status = 'SKIPPED', updated_at = ?
                WHERE user_id = ? AND target_turn_id = ? AND status = 'FAILED'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.setLong(2, userId);
            statement.setString(3, targetTurnId);
            statement.executeUpdate();
        }
    }

    @Override
    public void complete(
            long userId, String targetTurnId, List<UserProfileItem> items, LocalDateTime now) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                List<String> departments = listOn(connection, "SELECT name FROM departments");
                List<String> cities = listOn(connection, "SELECT city FROM city_policies");
                for (UserProfileItem item : items) {
                    if (item == null) {
                        continue;
                    }
                    String source = item.source();
                    if (!UserProfileItem.SOURCE_RULE.equals(source)
                            && !UserProfileItem.SOURCE_MODEL.equals(source)) {
                        continue;
                    }
                    Optional<String> canonical =
                            UserProfileTaxonomy.canonicalValue(
                                    item.category(), item.itemKey(), item.itemValue(),
                                    departments, cities);
                    if (canonical.isEmpty()) {
                        continue;
                    }
                    UserProfileItem candidate =
                            new UserProfileItem(
                                    0L,
                                    userId,
                                    UserProfileTaxonomy.normalizeCategory(item.category()),
                                    UserProfileTaxonomy.normalizeKey(item.itemKey()),
                                    canonical.get(),
                                    Math.max(0.0, Math.min(1.0, item.confidence())),
                                    source,
                                    item.sourceTurnId(),
                                    truncate(item.evidence(), 200),
                                    item.status(),
                                    0,
                                    null,
                                    now,
                                    now);
                    upsert(connection, candidate, now);
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE user_profile_extractions
                                SET status = 'DONE', detail = NULL, updated_at = ?
                                WHERE user_id = ? AND target_turn_id = ? AND status = 'CLAIMED'
                                """)) {
                    statement.setObject(1, now);
                    statement.setLong(2, userId);
                    statement.setString(3, targetTurnId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入工作偏好失败：" + e.getMessage(), e);
        }
    }

    private static List<String> listOn(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null && !value.isBlank()) {
                    rows.add(value);
                }
            }
            return rows;
        }
    }

    @Override
    public boolean fail(long userId, String targetTurnId, String detail, LocalDateTime now) {
        String sql =
                """
                UPDATE user_profile_extractions
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= ? THEN 'SKIPPED' ELSE 'FAILED' END,
                    detail = ?, updated_at = ?
                WHERE user_id = ? AND target_turn_id = ? AND status = 'CLAIMED'
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_ATTEMPTS);
            statement.setString(2, truncate(detail, 200));
            statement.setObject(3, now);
            statement.setLong(4, userId);
            statement.setString(5, targetTurnId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录偏好抽取失败状态失败：" + e.getMessage(), e);
        }
        return extraction(userId, targetTurnId)
                .map(row -> UserProfileExtraction.STATUS_SKIPPED.equals(row.status()))
                .orElse(false);
    }

    @Override
    public Optional<UserProfileExtraction> extraction(long userId, String targetTurnId) {
        String sql =
                """
                SELECT extraction_id, user_id, target_turn_id, status, attempts, detail,
                       created_at, updated_at
                FROM user_profile_extractions
                WHERE user_id = ? AND target_turn_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, targetTurnId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new UserProfileExtraction(
                                rs.getLong("extraction_id"),
                                rs.getLong("user_id"),
                                rs.getString("target_turn_id"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                rs.getString("detail"),
                                rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getTimestamp("updated_at").toLocalDateTime()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询偏好抽取凭证失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int cleanup(LocalDateTime before) {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM user_profile_extractions WHERE updated_at < ?")) {
            statement.setObject(1, before);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理偏好抽取凭证失败：" + e.getMessage(), e);
        }
    }

    private long upsert(Connection connection, UserProfileItem item, LocalDateTime now)
            throws SQLException {
        String category = UserProfileTaxonomy.normalizeCategory(item.category());
        String key = UserProfileTaxonomy.normalizeKey(item.itemKey());
        Optional<Long> existing = existingId(connection, item.userId(), category, key);
        if (existing.isEmpty()) {
            String insert =
                    """
                    INSERT INTO user_profile_items
                        (user_id, category, item_key, item_value, confidence, source,
                         source_turn_id, evidence, status, injected_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """;
            try (PreparedStatement statement =
                    connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, item.userId());
                statement.setString(2, category);
                statement.setString(3, key);
                statement.setString(4, item.itemValue());
                statement.setDouble(5, item.confidence());
                statement.setString(6, item.source());
                statement.setString(7, item.sourceTurnId());
                statement.setString(8, item.evidence());
                statement.setString(9, item.status());
                statement.setObject(10, now);
                statement.setObject(11, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
                // 并发插入同一键：退化为更新，最后写入者生效。
                return update(connection, item.userId(), category, key, item, now);
            }
        }
        return update(connection, item.userId(), category, key, item, now);
    }

    private long update(
            Connection connection,
            long userId,
            String category,
            String key,
            UserProfileItem item,
            LocalDateTime now)
            throws SQLException {
        // 用户主动停用的条目不会被自动抽取悄悄恢复；只有 MANUAL 写入可以重新启用。
        boolean manual = UserProfileItem.SOURCE_MANUAL.equals(item.source());
        String sql =
                """
                UPDATE user_profile_items
                SET item_value = ?, confidence = ?, source = ?, source_turn_id = ?,
                    evidence = ?, updated_at = ?,
                    status = CASE WHEN status = 'DISABLED' AND ? = FALSE THEN 'DISABLED' ELSE ? END
                WHERE user_id = ? AND category = ? AND item_key = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, item.itemValue());
            statement.setDouble(2, item.confidence());
            statement.setString(3, item.source());
            statement.setString(4, item.sourceTurnId());
            statement.setString(5, item.evidence());
            statement.setObject(6, now);
            statement.setBoolean(7, manual);
            statement.setString(8, item.status());
            statement.setLong(9, userId);
            statement.setString(10, category);
            statement.setString(11, key);
            statement.executeUpdate();
        }
        return existingId(connection, userId, category, key).orElse(0L);
    }

    private static Optional<Long> existingId(
            Connection connection, long userId, String category, String key) throws SQLException {
        String sql =
                """
                SELECT item_id FROM user_profile_items
                WHERE user_id = ? AND category = ? AND item_key = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, category);
            statement.setString(3, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    private static UserProfileItem itemById(Connection connection, long itemId)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM user_profile_items WHERE item_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, itemId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("偏好条目写入后不可见：" + itemId);
                }
                return map(rs);
            }
        }
    }

    private static int deleteAll(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM user_profile_items WHERE user_id = ?")) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        }
    }

    private static UserProfileItem map(ResultSet rs) throws SQLException {
        Timestamp lastInjected = rs.getTimestamp("last_injected_at");
        return new UserProfileItem(
                rs.getLong("item_id"),
                rs.getLong("user_id"),
                rs.getString("category"),
                rs.getString("item_key"),
                rs.getString("item_value"),
                rs.getDouble("confidence"),
                rs.getString("source"),
                rs.getString("source_turn_id"),
                rs.getString("evidence"),
                rs.getString("status"),
                rs.getInt("injected_count"),
                lastInjected == null ? null : lastInjected.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static boolean isDuplicateKey(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
