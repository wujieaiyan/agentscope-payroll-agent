package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditPage;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.support.AuditContext;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private H2AuditStore store;
    private AuditService service;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("audit-" + UUID.randomUUID());
        store = new H2AuditStore(database);
        service = new AuditService(store, 180);
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    void recordsBeforeAndAfterAndFiltersByActor() {
        beginAs(7L, "alice");
        service.record(
                "employee.save", "employee", "EMP001", Map.of("name", "旧名字"),
                Map.of("name", "新名字"));
        AuditContext.clear();

        beginAs(8L, "bob");
        service.record("policy.save", "city_policy", "北京", null, Map.of("social_min", 7000));
        AuditContext.clear();

        AuditPage alice =
                service.query(
                        new AuditQuery(
                                null, null, "alice", null, null, null, null, null, 1, 50));
        assertEquals(1, alice.total());
        AuditEntry entry = alice.items().get(0);
        assertEquals("employee.save", entry.action());
        assertEquals("employee", entry.resourceType());
        assertEquals("EMP001", entry.resourceId());
        assertTrue(entry.beforeJson().contains("旧名字"));
        assertTrue(entry.afterJson().contains("新名字"));
        assertEquals(AuditEntry.SUCCESS, entry.result());
        assertEquals(7L, entry.actorId());
    }

    @Test
    void redactsSensitiveFieldsInSnapshotsAndPayloads() {
        beginAs(7L, "alice");
        service.record(
                "user.create",
                "user",
                "9",
                null,
                Map.of(
                        "username", "newbie",
                        "password", "P@ssw0rd",
                        "bank_account_masked", "6222****1234"));
        String body =
                io.agentscope.examples.payroll.support.AuditSanitizer.requestBody(
                        "{\"old_password\":\"old\",\"nested\":{\"id_card\":\"110101199001011234\"}}");
        AuditContext.clear();

        AuditPage page = service.query(AuditQuery.firstPage(10));
        AuditEntry entry = page.items().get(0);
        assertTrue(entry.afterJson().contains("\"password\":\"***\""));
        assertFalse(entry.afterJson().contains("P@ssw0rd"));
        assertFalse(entry.afterJson().contains("6222"));
        assertTrue(body.contains("\"old_password\":\"***\""));
        assertTrue(body.contains("\"id_card\":\"***\""));
        assertFalse(body.contains("110101199001011234"));
    }

    @Test
    void recordsFailureResultAndMessage() {
        beginAs(7L, "alice");
        service.recordFailure("auth.login", "user", "alice", 401, "用户名或密码错误");
        AuditContext.clear();

        AuditPage page =
                service.query(
                        new AuditQuery(
                                null, null, null, null, null, null, AuditEntry.FAILURE, "密码", 1,
                                10));
        assertEquals(1, page.total());
        AuditEntry entry = page.items().get(0);
        assertEquals(401, entry.statusCode());
        assertEquals("用户名或密码错误", entry.message());
    }

    @Test
    void paginatesNewestFirstAndSearchesKeyword() {
        beginAs(1L, "alice");
        for (int i = 0; i < 3; i++) {
            service.record("employee.save", "employee", "EMP00" + i, null, Map.of("index", i));
        }
        AuditContext.clear();

        AuditPage first = service.query(new AuditQuery(null, null, null, null, null, null, null,
                null, 1, 2));
        assertEquals(3, first.total());
        assertEquals(2, first.items().size());
        assertEquals("EMP002", first.items().get(0).resourceId());

        AuditPage second = service.query(new AuditQuery(null, null, null, null, null, null, null,
                null, 2, 2));
        assertEquals(1, second.items().size());
        assertEquals("EMP000", second.items().get(0).resourceId());

        AuditPage keyword = service.query(new AuditQuery(null, null, null, null, null, null, null,
                "EMP001", 1, 10));
        assertEquals(1, keyword.total());
    }

    @Test
    void purgeRemovesEntriesOlderThanRetention() {
        store.insert(entryAt(LocalDateTime.now().minusDays(200), "old"));
        store.insert(entryAt(LocalDateTime.now().minusDays(2), "recent"));

        int removed = service.purgeExpired();

        assertEquals(1, removed);
        assertEquals(1, service.query(AuditQuery.firstPage(10)).total());
        assertEquals("recent", service.query(AuditQuery.firstPage(10)).items().get(0).resourceId());
    }

    @Test
    void exportStopsAtConfiguredMaximumWithoutDuplicates() {
        beginAs(1L, "alice");
        for (int i = 0; i < 3; i++) {
            service.record("employee.save", "employee", "EMP00" + i, null, null);
        }
        AuditContext.clear();

        assertEquals(3, service.export(AuditQuery.firstPage(1)).size());
    }

    private static void beginAs(long id, String username) {
        AuditContext.begin(
                new AuthUser(
                        id, username, username, "ADMIN", "管理员", null, null, Set.of("audit:read")),
                "127.0.0.1",
                "JUnit");
    }

    private static AuditEntry entryAt(LocalDateTime at, String resourceId) {
        return new AuditEntry(
                0L,
                at,
                1L,
                "alice",
                "employee.save",
                "employee",
                resourceId,
                null,
                null,
                null,
                "127.0.0.1",
                "JUnit",
                AuditEntry.SUCCESS,
                200,
                null,
                UUID.randomUUID().toString());
    }
}
