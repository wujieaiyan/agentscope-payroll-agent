package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.repository.AgentDefinitionRepository;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentDefinitionRepository} 的 JDBC 实现，H2 与 MySQL 共用同一套 SQL。
 *
 * <p>写路径在事务内 {@code SELECT ... FOR UPDATE} 锁住全部行后重算目录指纹，
 * 与前端回传的 revision 不一致即 409；行级 revision 每次更新递增。
 */
public final class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentDefinitionRepository.class);

    private static final String SELECT_COLUMNS = "id, payload_json, revision";

    private final PayrollDatabase database;

    public JdbcAgentDefinitionRepository(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public Snapshot load() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT " + SELECT_COLUMNS + " FROM agent_definition ORDER BY id");
                ResultSet rs = statement.executeQuery()) {
            Map<String, Long> revisions = new LinkedHashMap<>();
            List<AgentDefinition> items = new ArrayList<>();
            while (rs.next()) {
                String id = rs.getString("id");
                items.add(parse(id, rs.getString("payload_json")));
                revisions.put(id, rs.getLong("revision"));
            }
            return new Snapshot(List.copyOf(items), revisionOf(revisions));
        } catch (SQLException e) {
            throw new PayrollUnavailableException("读取智能体定义失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AgentDefinition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT " + SELECT_COLUMNS + " FROM agent_definition WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                        ? Optional.of(parse(id, rs.getString("payload_json")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PayrollUnavailableException("读取智能体定义失败：" + e.getMessage(), e);
        }
    }

    @Override
    public AgentDefinition save(
            AgentDefinition definition, String operator, String expectedRevision) {
        if (definition == null || definition.id() == null || definition.id().isBlank()) {
            throw new IllegalArgumentException("智能体定义缺少 id");
        }
        String payload = JsonSupport.toJson(definition);
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Long> revisions = lockRevisions(connection);
                String current = revisionOf(revisions);
                requireRevision(current, expectedRevision);
                Long existing = revisions.get(definition.id());
                if (existing == null) {
                    insert(connection, definition.id(), payload, 1, operator);
                } else {
                    update(connection, definition.id(), payload, existing + 1, operator);
                }
                connection.commit();
                return definition;
            } catch (SQLException e) {
                rollback(connection, e);
                if (integrityViolation(e)) {
                    throw new PayrollConflictException(
                            "配置已被他人修改，请刷新后重试", currentRevisionQuietly());
                }
                throw new PayrollUnavailableException("保存智能体定义失败：" + e.getMessage(), e);
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new PayrollUnavailableException("保存智能体定义失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id, String expectedRevision) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Long> revisions = lockRevisions(connection);
                if (!revisions.containsKey(id)) {
                    connection.rollback();
                    return false;
                }
                requireRevision(revisionOf(revisions), expectedRevision);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM agent_definition WHERE id = ?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                rollback(connection, e);
                throw new PayrollUnavailableException("删除智能体定义失败：" + e.getMessage(), e);
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new PayrollUnavailableException("删除智能体定义失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int importIfEmpty(List<AgentDefinition> definitions, String operator) {
        if (definitions == null || definitions.isEmpty()) {
            return 0;
        }
        Map<String, AgentDefinition> byId = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            if (definition != null && definition.id() != null && !definition.id().isBlank()) {
                byId.putIfAbsent(definition.id(), definition);
            }
        }
        List<AgentDefinition> distinct = new ArrayList<>(byId.values());
        String operatorValue = operator == null ? "" : operator;
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                if (!lockRevisions(connection).isEmpty()) {
                    connection.rollback();
                    return 0;
                }
                int imported = 0;
                for (AgentDefinition definition : distinct) {
                    if (definition.id() == null || definition.id().isBlank()) {
                        continue;
                    }
                    insert(
                            connection,
                            definition.id(),
                            JsonSupport.toJson(definition),
                            1,
                            operatorValue);
                    imported++;
                }
                connection.commit();
                return imported;
            } catch (SQLException e) {
                rollback(connection, e);
                if (integrityViolation(e)) {
                    log.info("[Agent] 另一节点已完成 Nacos 导入，本次跳过（{}）", e.getMessage());
                    return 0;
                }
                throw new PayrollUnavailableException("导入智能体定义失败：" + e.getMessage(), e);
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new PayrollUnavailableException("导入智能体定义失败：" + e.getMessage(), e);
        }
    }

    /** 锁住全部行后返回 id → 行级 revision；空表时由唯一键兜底并发插入。 */
    private Map<String, Long> lockRevisions(Connection connection) throws SQLException {
        Map<String, Long> revisions = new LinkedHashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, revision FROM agent_definition ORDER BY id FOR UPDATE");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                revisions.put(rs.getString("id"), rs.getLong("revision"));
            }
        }
        return revisions;
    }

    private static void insert(
            Connection connection,
            String id,
            String payload,
            long revision,
            String operator)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        INSERT INTO agent_definition
                            (id, payload_json, revision, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, id);
            statement.setString(2, payload);
            statement.setLong(3, revision);
            statement.setString(4, operator);
            statement.setObject(5, LocalDateTime.now());
            statement.executeUpdate();
        }
    }

    private static void update(
            Connection connection,
            String id,
            String payload,
            long revision,
            String operator)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        UPDATE agent_definition
                        SET payload_json = ?, revision = ?, updated_by = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
            statement.setString(1, payload);
            statement.setLong(2, revision);
            statement.setString(3, operator);
            statement.setObject(4, LocalDateTime.now());
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    private static AgentDefinition parse(String id, String payload) {
        try {
            return JsonSupport.fromJson(payload, AgentDefinition.class);
        } catch (RuntimeException e) {
            throw new PayrollUnavailableException(
                    "解析智能体定义 " + id + " 失败：" + e.getMessage(), e);
        }
    }

    private static void requireRevision(String current, String expected) {
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (!current.equals(expected)) {
            throw new PayrollConflictException("配置已被他人修改，请刷新后重试", current);
        }
    }

    /** 目录指纹：按 id 排序拼接行级 revision，任一行的写入都会改变整份指纹。 */
    private static String revisionOf(Map<String, Long> revisions) {
        StringBuilder raw = new StringBuilder();
        revisions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> raw.append(entry.getKey()).append(':').append(entry.getValue()).append('\n'));
        return sha256(raw.toString());
    }

    private String currentRevisionQuietly() {
        try {
            return load().revision();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(String raw) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean integrityViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
        }
    }
}
