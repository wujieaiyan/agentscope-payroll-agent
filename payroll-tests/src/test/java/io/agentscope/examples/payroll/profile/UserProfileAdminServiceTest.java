package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2UserProfileStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserProfileAdminServiceTest {

    private static final long USER = 7L;
    private static final long OTHER = 8L;

    private PayrollDatabase database;
    private H2UserProfileStore store;
    private H2AuditStore auditStore;
    private UserProfileAdminService service;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("profile-admin-" + System.nanoTime());
        store = new H2UserProfileStore(database);
        auditStore = new H2AuditStore(database);
        service = new UserProfileAdminService(store, new AuditService(auditStore, 180), true);
    }

    @AfterEach
    void tearDown() {
        ProfileMemory.reset();
    }

    @Test
    @DisplayName("开关关闭时读写被拒绝，合规删除仍可执行")
    void disabledGate() {
        UserProfileAdminService disabled =
                new UserProfileAdminService(store, new AuditService(auditStore, 180), false);

        assertThrows(PayrollException.class, () -> disabled.list(USER));
        assertThrows(PayrollException.class, () -> disabled.options(USER));
        assertThrows(
                PayrollException.class,
                () -> disabled.create(USER, "ROLE", "region", "上海"));
        assertEquals(0, disabled.purge(USER));
    }

    @Test
    @DisplayName("新增时按词典规范化值并标记为手动来源")
    void createCanonicalizesValue() {
        UserProfileAdminService.ItemView created =
                service.create(USER, "scope", "default_department", " 研发部 ");

        assertEquals("SCOPE", created.category());
        assertEquals("default_department", created.itemKey());
        assertEquals("研发部", created.itemValue());
        assertEquals("常用部门", created.label());
        assertEquals(UserProfileItem.SOURCE_MANUAL, created.source());
        assertEquals(UserProfileItem.STATUS_ACTIVE, created.status());
        assertEquals(1.0, created.confidence());

        List<UserProfileAdminService.ItemView> items = service.list(USER);
        assertEquals(1, items.size());
        assertEquals(created.itemId(), items.get(0).itemId());
    }

    @Test
    @DisplayName("词表外的键或值返回业务异常")
    void rejectsUnknownKeyOrValue() {
        PayrollException badKey =
                assertThrows(
                        PayrollException.class,
                        () -> service.create(USER, "HACK", "anything", "上海"));
        assertTrue(badKey.getMessage().contains("不支持的偏好类型"), badKey.getMessage());

        PayrollException badValue =
                assertThrows(
                        PayrollException.class,
                        () -> service.create(USER, "SCOPE", "default_department", "不存在部"));
        assertTrue(badValue.getMessage().contains("不在允许范围"), badValue.getMessage());
    }

    @Test
    @DisplayName("跨用户修改或删除他条目的请求一律视为不存在")
    void rejectsCrossUserAccess() {
        UserProfileAdminService.ItemView created = service.create(USER, "ROLE", "region", "上海");

        assertThrows(
                PayrollNotFoundException.class,
                () -> service.update(OTHER, created.itemId(), "销售部", null));
        assertThrows(
                PayrollNotFoundException.class,
                () -> service.delete(OTHER, created.itemId()));
        assertEquals(1, service.list(USER).size());
    }

    @Test
    @DisplayName("停用后再启用，值编辑会恢复 ACTIVE")
    void togglesStatus() {
        UserProfileAdminService.ItemView created = service.create(USER, "ROLE", "region", "上海");

        UserProfileAdminService.ItemView disabled =
                service.update(USER, created.itemId(), null, "disabled");
        assertEquals("DISABLED", disabled.status());
        assertTrue(store.active(USER, 10).isEmpty());

        UserProfileAdminService.ItemView enabled =
                service.update(USER, created.itemId(), null, "ACTIVE");
        assertEquals("ACTIVE", enabled.status());

        service.update(USER, created.itemId(), null, "DISABLED");
        UserProfileAdminService.ItemView valueEdit =
                service.update(USER, created.itemId(), "深圳", null);
        assertEquals("ACTIVE", valueEdit.status());
        assertEquals("深圳", valueEdit.itemValue());
    }

    @Test
    @DisplayName("清空返回条数，合规删除同时移除条目与抽取凭证")
    void clearAndPurge() {
        service.create(USER, "ROLE", "region", "上海");
        service.create(USER, "TOPIC", "focus", "个税");
        assertEquals(2, service.clear(USER));
        assertTrue(service.list(USER).isEmpty());

        service.create(USER, "ROLE", "region", "上海");
        store.claim(USER, "turn-1", LocalDateTime.now());
        assertEquals(2, service.purge(USER));
        assertTrue(store.items(USER, true).isEmpty());
        assertTrue(store.extraction(USER, "turn-1").isEmpty());
    }

    @Test
    @DisplayName("审计只落值哈希与键，不落值原文")
    void auditStoresHashOnly() {
        service.create(USER, "ROLE", "region", "上海");

        List<AuditEntry> entries = auditStore.find(AuditQuery.firstPage(10));
        AuditEntry created =
                entries.stream()
                        .filter(e -> UserProfileAdminService.ACTION_CREATE.equals(e.action()))
                        .findFirst()
                        .orElseThrow();

        String after = created.afterJson();
        assertTrue(after.contains("value_hash"), after);
        assertTrue(after.contains("region"), after);
        assertFalse(after.contains("上海"), after);
    }
}
