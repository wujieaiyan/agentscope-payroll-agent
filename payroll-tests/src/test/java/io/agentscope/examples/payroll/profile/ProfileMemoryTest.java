package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2UserProfileStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileMemoryTest {

    private static final long USER = 7L;

    private PayrollDatabase database;
    private H2UserProfileStore store;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("profile-memory-" + System.nanoTime());
        store = new H2UserProfileStore(database);
    }

    @AfterEach
    void tearDown() {
        ProfileMemory.reset();
    }

    @Test
    @DisplayName("开关关闭或画像为空时不注入")
    void emptyWhenDisabledOrBlank() {
        ProfileMemory.configure(store, false);
        assertFalse(ProfileMemory.load(USER, "帮我算工资").present());

        ProfileMemory.configure(store, true);
        ProfileMemory.Injection injection = ProfileMemory.load(USER, "帮我算工资");
        assertFalse(injection.present());
        assertEquals("", injection.cacheSuffix());
    }

    @Test
    @DisplayName("注入块带边界声明、回显提示和用户级缓存后缀")
    void injectsWithBoundariesAndSuffix() {
        store.upsert(item("ROLE", "region", "上海"));
        store.upsert(item("TOPIC", "focus", "个税"));
        ProfileMemory.configure(store, true);

        ProfileMemory.Injection injection = ProfileMemory.load(USER, "帮我算工资");
        assertTrue(injection.present());
        assertTrue(injection.block().contains("不构成授权"));
        assertTrue(injection.block().contains("职责范围：上海"));
        assertTrue(injection.block().contains("用一句话说明"));
        assertEquals(2, injection.itemCount());
        assertTrue(injection.cacheSuffix().startsWith("|pu=" + USER + ":"), injection.cacheSuffix());
    }

    @Test
    @DisplayName("问题提到其它城市时抑制职责条目，但仍注入无冲突条目")
    void suppressesConflictingRegion() {
        store.upsert(item("ROLE", "region", "上海"));
        store.upsert(item("TOPIC", "focus", "个税"));
        ProfileMemory.configure(store, true);

        ProfileMemory.Injection injection = ProfileMemory.load(USER, "深圳这个月个税怎么算");
        assertTrue(injection.present());
        assertEquals(1, injection.itemCount());
        assertEquals(1, injection.conflicts());
        assertFalse(injection.block().contains("上海"), injection.block());
        assertTrue(injection.block().contains("个税"));
    }

    @Test
    @DisplayName("超出条数与字符预算时截断")
    void respectsBudget() {
        store.upsert(item("ROLE", "region", "上海"));
        store.upsert(item("SCOPE", "default_department", "研发部"));
        store.upsert(item("TOPIC", "focus", "个税"));

        ProfileMemory.configure(store, true, 2, 600);
        assertEquals(2, ProfileMemory.load(USER, "帮我算工资").itemCount());

        ProfileMemory.reset();
        ProfileMemory.configure(store, true, 10, 100);
        ProfileMemory.Injection limited = ProfileMemory.load(USER, "帮我算工资");
        assertEquals(1, limited.itemCount());
        assertTrue(limited.chars() <= 100, "chars=" + limited.chars());
    }

    @Test
    @DisplayName("画像写操作立即反映到下一次注入，缓存后缀随之变化")
    void cacheRefreshesOnProfileChange() {
        store.upsert(item("ROLE", "region", "上海"));
        ProfileMemory.configure(store, true);

        ProfileMemory.Injection first = ProfileMemory.load(USER, "帮我算工资");
        assertEquals(1, first.itemCount());
        store.upsert(item("TOPIC", "focus", "个税"));
        // 抽取在 runtime、删除在 web，读取侧按 revision 比对刷新，不依赖同进程失效。
        ProfileMemory.Injection refreshed = ProfileMemory.load(USER, "帮我算工资");
        assertEquals(2, refreshed.itemCount());
        assertNotEquals(first.cacheSuffix(), refreshed.cacheSuffix());
    }

    @Test
    @DisplayName("注入计数按用户汇总落库")
    void flushTouchesUpdatesCounters() {
        store.upsert(item("ROLE", "region", "上海"));
        ProfileMemory.configure(store, true);

        ProfileMemory.load(USER, "帮我算工资");
        ProfileMemory.flushTouches();

        UserProfileItem touched = store.items(USER, true).get(0);
        assertEquals(1, touched.injectedCount());
    }

    @Test
    @DisplayName("读取异常时降级为空块，不阻断本轮")
    void degradesOnReadFailure() {
        ProfileMemory.configure(new BrokenStore(), true);
        ProfileMemory.Injection injection = ProfileMemory.load(USER, "帮我算工资");
        assertFalse(injection.present());
        assertEquals(0, injection.itemCount());
    }

    private UserProfileItem item(String category, String key, String value) {
        return new UserProfileItem(
                0L,
                USER,
                category,
                key,
                value,
                0.9,
                UserProfileItem.SOURCE_MANUAL,
                null,
                "手动设置：" + value,
                UserProfileItem.STATUS_ACTIVE,
                0,
                null,
                null,
                null);
    }

    private static final class BrokenStore implements UserProfileStore {

        @Override
        public List<UserProfileItem> items(long userId, boolean includeInactive) {
            throw new IllegalStateException("boom");
        }

        @Override
        public List<UserProfileItem> active(long userId, int limit) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Optional<String> revision(long userId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Optional<UserProfileItem> item(long itemId, long userId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public UserProfileItem upsert(UserProfileItem item) {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean updateStatus(long itemId, long userId, String status) {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean delete(long itemId, long userId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int deleteAll(long userId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int purge(long userId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int touchInjected(long userId, List<Long> itemIds, LocalDateTime now) {
            throw new IllegalStateException("boom");
        }

        @Override
        public List<String> departments() {
            throw new IllegalStateException("boom");
        }

        @Override
        public List<String> cities() {
            throw new IllegalStateException("boom");
        }

        @Override
        public List<UserProfileTurn> window(long userId, int maxTurns) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Claim claim(long userId, String targetTurnId, LocalDateTime now) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void complete(
                long userId, String targetTurnId, List<UserProfileItem> items, LocalDateTime now) {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean fail(long userId, String targetTurnId, String detail, LocalDateTime now) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Optional<UserProfileExtraction> extraction(long userId, String targetTurnId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int cleanup(LocalDateTime before) {
            throw new IllegalStateException("boom");
        }
    }
}
