package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2UserProfileStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);
    private static final long USER = 7L;

    private PayrollDatabase database;
    private H2UserProfileStore store;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("profile-store-" + System.nanoTime());
        store = new H2UserProfileStore(database);
    }

    @Test
    @DisplayName("同键 upsert 只保留一行并保留创建时间，列表按类别优先级排序")
    void upsertKeepsSingleRowAndOrder() {
        UserProfileItem focus = store.upsert(item("TOPIC", "focus", "个税", "RULE", "ACTIVE"));
        UserProfileItem region = store.upsert(item("ROLE", "region", "上海", "RULE", "ACTIVE"));
        store.upsert(item("PREFERENCE", "caliber", "应发工资", "RULE", "ACTIVE"));
        store.upsert(item("SCOPE", "default_department", "研发部", "RULE", "ACTIVE"));

        UserProfileItem updated =
                store.upsert(item("ROLE", "region", "深圳", "MODEL", "ACTIVE"));
        assertEquals(region.itemId(), updated.itemId());
        assertEquals(region.createdAt(), updated.createdAt());
        assertEquals("深圳", updated.itemValue());
        assertEquals("MODEL", updated.source());

        List<UserProfileItem> rows = store.items(USER, true);
        assertEquals(4, rows.size());
        assertEquals("ROLE", rows.get(0).category());
        assertEquals("SCOPE", rows.get(1).category());
        assertEquals("PREFERENCE", rows.get(2).category());
        assertEquals("TOPIC", rows.get(3).category());
        assertEquals(focus.itemId(), rows.get(3).itemId());
    }

    @Test
    @DisplayName("自动抽取不会唤醒用户停用的条目，只有手动写入才会重新启用")
    void disabledRowSurvivesAutomaticUpsert() {
        UserProfileItem created = store.upsert(item("ROLE", "region", "上海", "RULE", "ACTIVE"));
        assertTrue(store.updateStatus(created.itemId(), USER, "DISABLED"));

        store.upsert(item("ROLE", "region", "深圳", "MODEL", "ACTIVE"));
        UserProfileItem afterExtraction = store.items(USER, true).get(0);
        assertEquals("DISABLED", afterExtraction.status());
        assertEquals("深圳", afterExtraction.itemValue());

        store.upsert(item("ROLE", "region", "上海", "MANUAL", "ACTIVE"));
        assertEquals("ACTIVE", store.items(USER, true).get(0).status());
    }

    @Test
    @DisplayName("active 只返回 ACTIVE，revision 在无启用条目时为空")
    void activeAndRevision() {
        assertTrue(store.active(USER, 10).isEmpty());
        assertTrue(store.revision(USER).isEmpty());

        UserProfileItem active = store.upsert(item("ROLE", "region", "上海", "RULE", "ACTIVE"));
        store.upsert(item("TOPIC", "focus", "个税", "RULE", "STALE"));
        store.upsert(item("SCOPE", "default_period", "本月", "RULE", "DISABLED"));

        assertEquals(1, store.active(USER, 10).size());
        assertEquals(active.itemId(), store.active(USER, 10).get(0).itemId());
        assertTrue(store.revision(USER).isPresent());

        store.updateStatus(active.itemId(), USER, "DISABLED");
        assertTrue(store.revision(USER).isEmpty());
    }

    @Test
    @DisplayName("认领后重复认领为 DUPLICATE，租约过期可被接管")
    void claimDuplicateAndLeaseTakeover() {
        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "turn-a", NOW));
        assertEquals(UserProfileStore.Claim.DUPLICATE, store.claim(USER, "turn-a", NOW));
        assertEquals(
                UserProfileStore.Claim.DUPLICATE,
                store.claim(USER, "turn-a", NOW.plusMinutes(5)));
        assertEquals(
                UserProfileStore.Claim.CLAIMED,
                store.claim(USER, "turn-a", NOW.plusMinutes(11)));

        UserProfileExtraction extraction = store.extraction(USER, "turn-a").orElseThrow();
        assertEquals("CLAIMED", extraction.status());
        assertEquals(NOW.plusMinutes(11), extraction.updatedAt());
    }

    @Test
    @DisplayName("失败进入冷却，冷却后可重试，累计三次失败转 SKIPPED")
    void failureCooldownAndSkip() {
        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "turn-b", NOW));
        assertFalse(store.fail(USER, "turn-b", "boom", NOW));
        UserProfileExtraction first = store.extraction(USER, "turn-b").orElseThrow();
        assertEquals("FAILED", first.status());
        assertEquals(1, first.attempts());

        assertEquals(
                UserProfileStore.Claim.COOLDOWN,
                store.claim(USER, "turn-b", NOW.plusMinutes(30)));
        assertEquals(
                UserProfileStore.Claim.CLAIMED,
                store.claim(USER, "turn-b", NOW.plusMinutes(61)));

        assertFalse(store.fail(USER, "turn-b", "boom", NOW.plusMinutes(61)));
        UserProfileExtraction second = store.extraction(USER, "turn-b").orElseThrow();
        assertEquals("FAILED", second.status());
        assertEquals(2, second.attempts());

        assertEquals(
                UserProfileStore.Claim.CLAIMED,
                store.claim(USER, "turn-b", NOW.plusHours(3)));
        assertTrue(store.fail(USER, "turn-b", "boom", NOW.plusHours(3)));
        UserProfileExtraction third = store.extraction(USER, "turn-b").orElseThrow();
        assertEquals("SKIPPED", third.status());
        assertEquals(3, third.attempts());

        assertEquals(
                UserProfileStore.Claim.DUPLICATE,
                store.claim(USER, "turn-b", NOW.plusHours(4)));
    }

    @Test
    @DisplayName("complete 只写入规则/模型来源且命中词典的条目")
    void completeFiltersAndCanonicalizes() {
        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "turn-c", NOW));
        List<UserProfileItem> candidates =
                List.of(
                        item("ROLE", "region", "上海", "RULE", "ACTIVE"),
                        item("SCOPE", "default_department", " 研发部 ", "MODEL", "ACTIVE"),
                        item("ROLE", "department", "销售部", "MODEL", "ACTIVE"),
                        item("TOPIC", "focus", "火箭", "MODEL", "ACTIVE"),
                        item("PREFERENCE", "caliber", "应发工资", "MANUAL", "ACTIVE"));

        store.complete(USER, "turn-c", candidates, NOW);

        List<UserProfileItem> saved = store.active(USER, 10);
        assertEquals(3, saved.size());
        UserProfileItem department =
                saved.stream()
                        .filter(row -> "default_department".equals(row.itemKey()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("研发部", department.itemValue());
        assertEquals("DONE", store.extraction(USER, "turn-c").orElseThrow().status());
    }

    @Test
    @DisplayName("窗口按游标推进，排除非成功轮次并受 maxTurns 限制")
    void windowFollowsCursor() {
        long session = 100L;
        insertTurn("t1", USER, session, NOW, "第一轮", "SUCCEEDED");
        insertTurn("t2", USER, session, NOW.plusMinutes(1), "第二轮", "SUCCEEDED");
        insertTurn("t-fail", USER, session, NOW.plusMinutes(2), "失败轮", "FAILED");
        insertTurn("t3", USER, session, NOW.plusMinutes(3), "第三轮", "SUCCEEDED");
        insertTurn("t4", USER, session, NOW.plusMinutes(4), "第四轮", "SUCCEEDED");
        insertTurn("t5", USER, session, NOW.plusMinutes(5), "第五轮", "SUCCEEDED");
        insertTurn("t6", USER, session, NOW.plusMinutes(6), "第六轮", "SUCCEEDED");
        insertTurn("other-1", 8L, 200L, NOW, "别人的", "SUCCEEDED");

        List<UserProfileTurn> all = store.window(USER, 10);
        assertEquals(6, all.size());
        assertEquals("t1", all.get(0).turnId());
        assertEquals("第一轮", all.get(0).message());
        assertEquals(1, store.window(8L, 10).size());

        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "t3", NOW.plusHours(1)));
        store.complete(USER, "t3", List.of(), NOW.plusHours(1));

        List<UserProfileTurn> afterDone = store.window(USER, 10);
        assertEquals(3, afterDone.size());
        assertEquals("t4", afterDone.get(0).turnId());
        assertEquals(2, store.window(USER, 2).size());
        assertEquals("t4", store.window(USER, 1).get(0).turnId());

        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "t5", NOW.plusHours(2)));
        store.fail(USER, "t5", "boom", NOW.plusHours(2));
        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "t5", NOW.plusHours(4)));
        store.fail(USER, "t5", "boom", NOW.plusHours(4));
        assertEquals(UserProfileStore.Claim.CLAIMED, store.claim(USER, "t5", NOW.plusHours(6)));
        store.fail(USER, "t5", "boom", NOW.plusHours(6));

        List<UserProfileTurn> afterSkipped = store.window(USER, 10);
        assertEquals(1, afterSkipped.size());
        assertEquals("t6", afterSkipped.get(0).turnId());
    }

    @Test
    @DisplayName("注入计数可累加，清理与合规删除会移除凭证")
    void touchCleanupPurge() {
        UserProfileItem item = store.upsert(item("ROLE", "region", "上海", "RULE", "ACTIVE"));
        assertEquals(1, store.touchInjected(USER, List.of(item.itemId()), NOW));
        UserProfileItem touched = store.item(item.itemId(), USER).orElseThrow();
        assertEquals(1, touched.injectedCount());
        assertNotNull(touched.lastInjectedAt());

        store.claim(USER, "old-turn", NOW.minusDays(40));
        store.claim(USER, "fresh-turn", NOW);
        assertEquals(1, store.cleanup(NOW.minusDays(30)));

        store.upsert(item("TOPIC", "focus", "个税", "RULE", "ACTIVE"));
        assertEquals(3, store.purge(USER));
        assertTrue(store.items(USER, true).isEmpty());
        assertTrue(store.extraction(USER, "fresh-turn").isEmpty());
    }

    private UserProfileItem item(
            String category, String key, String value, String source, String status) {
        return new UserProfileItem(
                0L,
                USER,
                category,
                key,
                value,
                0.9,
                source,
                "turn-1",
                "证据",
                status,
                0,
                null,
                null,
                null);
    }

    private void insertTurn(
            String turnId,
            long userId,
            long sessionId,
            LocalDateTime startedAt,
            String content,
            String status) {
        long messageId = insertMessage(sessionId, content, turnId);
        String sql =
                """
                INSERT INTO chat_turns
                    (turn_id, session_id, user_id, user_message_id, assistant_message_id,
                     status, started_at, updated_at, heartbeat_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId);
            statement.setLong(2, sessionId);
            statement.setLong(3, userId);
            statement.setLong(4, messageId);
            statement.setString(5, status);
            statement.setObject(6, startedAt);
            statement.setObject(7, startedAt);
            statement.setObject(8, startedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long insertMessage(long sessionId, String content, String turnId) {
        String sql =
                """
                INSERT INTO chat_messages (session_id, role, content, turn_id, created_at)
                VALUES (?, 'USER', ?, ?, ?)
                """;
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sessionId);
            statement.setString(2, content);
            statement.setString(3, turnId);
            statement.setObject(4, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
