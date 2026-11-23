package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2UserProfileStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserProfileServiceTest {

    private static final long USER = 7L;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 20, 9, 0);

    private PayrollDatabase database;
    private H2UserProfileStore store;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("profile-service-" + System.nanoTime());
        store = new H2UserProfileStore(database);
    }

    @AfterEach
    void tearDown() {
        UserProfileRecorder.reset();
        ProfileMemory.reset();
    }

    @Test
    @DisplayName("达到触发轮次后规则抽取写入画像并推进游标")
    void extractsAfterTrigger() {
        for (int i = 0; i < 5; i++) {
            insertTurn("t" + i, USER, BASE.plusMinutes(i), "我负责上海区域的工资核算");
        }
        service = new UserProfileService(store, extractor(), true);

        service.process(USER);

        assertEquals(1, service.claimedCount());
        assertEquals(1, service.doneCount());
        assertEquals(0, service.failedCount());

        List<UserProfileItem> items = store.active(USER, 10);
        assertEquals(1, items.size());
        assertEquals(UserProfileTaxonomy.KEY_REGION, items.get(0).itemKey());
        assertEquals("上海", items.get(0).itemValue());
        assertEquals("DONE", store.extraction(USER, "t4").orElseThrow().status());

        service.process(USER);
        assertEquals(1, service.claimedCount());
    }

    @Test
    @DisplayName("不足触发轮次时不认领")
    void skipsBelowTrigger() {
        for (int i = 0; i < 4; i++) {
            insertTurn("t" + i, USER, BASE.plusMinutes(i), "我负责上海区域的工资核算");
        }
        service = new UserProfileService(store, extractor(), true);

        service.process(USER);

        assertEquals(0, service.claimedCount());
        assertTrue(store.active(USER, 10).isEmpty());
    }

    @Test
    @DisplayName("模型解析失败记 FAILED，并在冷却期内不重复认领")
    void recordsFailureOnce() {
        for (int i = 0; i < 5; i++) {
            insertTurn("t" + i, USER, BASE.plusMinutes(i), "今天天气不错");
        }
        UserProfileModelClient brokenModel =
                new UserProfileModelClient() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public String extract(String instruction, String question, String sessionKey) {
                        return "not-json";
                    }
                };
        service = new UserProfileService(store, new UserProfileExtractor(brokenModel), true);

        service.process(USER);
        assertEquals(1, service.failedCount());
        assertEquals(0, service.skippedCount());
        assertEquals("FAILED", store.extraction(USER, "t4").orElseThrow().status());

        service.process(USER);
        assertEquals(1, service.failedCount());
        assertEquals("FAILED", store.extraction(USER, "t4").orElseThrow().status());
    }

    @Test
    @DisplayName("第三次失败转 SKIPPED 推进游标")
    void skipsAfterThirdFailure() {
        for (int i = 0; i < 5; i++) {
            insertTurn("t" + i, USER, BASE.plusMinutes(i), "今天天气不错");
        }
        LocalDateTime first = LocalDateTime.now().minusHours(4);
        LocalDateTime second = LocalDateTime.now().minusHours(2);
        store.claim(USER, "t4", first);
        store.fail(USER, "t4", "boom", first);
        store.claim(USER, "t4", second);
        store.fail(USER, "t4", "boom", second);

        UserProfileModelClient brokenModel =
                new UserProfileModelClient() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public String extract(String instruction, String question, String sessionKey) {
                        return "not-json";
                    }
                };
        service = new UserProfileService(store, new UserProfileExtractor(brokenModel), true);

        service.process(USER);

        assertEquals(1, service.failedCount());
        assertEquals(1, service.skippedCount());
        assertEquals("SKIPPED", store.extraction(USER, "t4").orElseThrow().status());

        service.process(USER);
        assertEquals(1, service.failedCount());
    }

    @Test
    @DisplayName("关闭或无用户时不处理")
    void disabledIsNoop() {
        for (int i = 0; i < 5; i++) {
            insertTurn("t" + i, USER, BASE.plusMinutes(i), "我负责上海区域的工资核算");
        }
        new UserProfileService(store, extractor(), false).process(USER);
        new UserProfileService(store, extractor(), true).process(0L);

        assertTrue(store.active(USER, 10).isEmpty());
    }

    private UserProfileExtractor extractor() {
        return new UserProfileExtractor(UserProfileModelClient.disabled());
    }

    private void insertTurn(String turnId, long userId, LocalDateTime startedAt, String content) {
        long messageId = insertMessage(content, turnId);
        String sql =
                """
                INSERT INTO chat_turns
                    (turn_id, session_id, user_id, user_message_id, assistant_message_id,
                     status, started_at, updated_at, heartbeat_at)
                VALUES (?, 1, ?, ?, 0, 'SUCCEEDED', ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId);
            statement.setLong(2, userId);
            statement.setLong(3, messageId);
            statement.setObject(4, startedAt);
            statement.setObject(5, startedAt);
            statement.setObject(6, startedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long insertMessage(String content, String turnId) {
        String sql =
                """
                INSERT INTO chat_messages (session_id, role, content, turn_id, created_at)
                VALUES (1, 'USER', ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, content);
            statement.setString(2, turnId);
            statement.setObject(3, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
