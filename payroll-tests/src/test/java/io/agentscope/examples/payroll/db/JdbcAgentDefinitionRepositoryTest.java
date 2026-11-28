package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.repository.AgentDefinitionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 智能体定义落库：内容往返、行级 revision 递增、写前 409、空表导入幂等。 */
class JdbcAgentDefinitionRepositoryTest {

    private PayrollDatabase database;
    private JdbcAgentDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("agent-def-" + System.nanoTime());
        repository = new JdbcAgentDefinitionRepository(database);
    }

    @Test
    @DisplayName("保存后可按 id 读回，字段完整往返")
    void saveAndLoadRoundTrip() {
        AgentDefinition saved =
                repository.save(agent("agent-a", "个税顾问"), "admin", null);

        assertEquals("agent-a", saved.id());
        AgentDefinition loaded = repository.find("agent-a").orElseThrow();
        assertEquals("个税顾问", loaded.name());
        assertEquals(List.of("税务"), loaded.tags());
        assertEquals(List.of(AgentDefinition.CAPABILITY_CALC), loaded.capabilities());
        assertNotNull(loaded.createdAt());
        assertEquals(1, repository.load().items().size());
        assertTrue(repository.find("missing").isEmpty());
    }

    @Test
    @DisplayName("每次写入目录指纹都变化，行级 revision 从 1 递增到 2")
    void revisionIncrementsOnUpdate() throws Exception {
        String empty = repository.load().revision();
        repository.save(agent("agent-a", "个税顾问"), "admin", empty);
        String afterCreate = repository.load().revision();
        assertFalse(empty.equals(afterCreate));

        repository.save(agent("agent-a", "个税专家"), "admin", afterCreate);
        String afterUpdate = repository.load().revision();
        assertFalse(afterCreate.equals(afterUpdate));
        assertEquals(2, rowRevision("agent-a"));
        assertEquals("个税专家", repository.find("agent-a").orElseThrow().name());
    }

    @Test
    @DisplayName("携带过期 revision 写入返回 409 且不覆盖他人修改")
    void staleRevisionIsRejected() {
        String base = repository.load().revision();
        repository.save(agent("agent-a", "个税顾问"), "admin", base);
        String stale = base;

        PayrollConflictException error =
                assertThrows(
                        PayrollConflictException.class,
                        () -> repository.save(agent("agent-a", "覆盖者"), "other", stale));
        assertEquals(repository.load().revision(), error.currentRevision());
        assertEquals("个税顾问", repository.find("agent-a").orElseThrow().name());
    }

    @Test
    @DisplayName("两个并发更新同一份定义只有一个成功，另一个收到 409")
    void concurrentWritersOnlyOneWins() throws Exception {
        String base = repository.load().revision();
        repository.save(agent("agent-shared", "基线"), "admin", base);
        String expected = repository.load().revision();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first =
                    pool.submit(() -> updateWith(start, "agent-shared", "线程一", expected));
            Future<Boolean> second =
                    pool.submit(() -> updateWith(start, "agent-shared", "线程二", expected));
            start.countDown();
            boolean firstOk = first.get(10, TimeUnit.SECONDS);
            boolean secondOk = second.get(10, TimeUnit.SECONDS);
            assertTrue(firstOk ^ secondOk, "应恰好一个成功：" + firstOk + "/" + secondOk);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, repository.load().items().size());
        assertEquals(2, rowRevision("agent-shared"));
    }

    @Test
    @DisplayName("删除校验 revision；删除不存在的 id 返回 false")
    void deleteChecksRevision() {
        String base = repository.load().revision();
        repository.save(agent("agent-a", "个税顾问"), "admin", base);
        String current = repository.load().revision();

        assertThrows(
                PayrollConflictException.class,
                () -> repository.delete("agent-a", base));
        assertTrue(repository.delete("agent-a", current));
        assertFalse(repository.delete("agent-a", current));
        assertTrue(repository.find("agent-a").isEmpty());
    }

    @Test
    @DisplayName("每次保存追加版本，回滚追加新版本并恢复旧内容")
    void versionsAppendAndRollbackRestoresPayload() throws Exception {
        String base = repository.load().revision();
        repository.save(agent("agent-a", "v1 名字"), "admin", base, "首次创建");
        String afterCreate = repository.load().revision();
        repository.save(agent("agent-a", "v2 名字"), "admin", afterCreate, "改名");

        List<AgentDefinitionRepository.Version> versions = repository.versions("agent-a");
        assertEquals(
                List.of(2, 1),
                versions.stream().map(AgentDefinitionRepository.Version::versionNo).toList());
        assertTrue(versions.get(0).current());
        assertEquals("改名", versions.get(0).changeNote());
        assertEquals("admin", versions.get(0).createdBy());
        assertFalse(versions.get(1).current());

        String beforeRollback = repository.load().revision();
        AgentDefinition restored = repository.rollback("agent-a", 1, beforeRollback, "auditor");
        assertEquals("v1 名字", restored.name());
        assertEquals("v1 名字", repository.find("agent-a").orElseThrow().name());
        assertEquals(3, rowRevision("agent-a"));

        List<AgentDefinitionRepository.Version> after = repository.versions("agent-a");
        assertEquals(3, after.get(0).versionNo());
        assertEquals("回滚到 v1", after.get(0).changeNote());
        assertEquals("auditor", after.get(0).createdBy());
        assertTrue(after.get(0).current());
        assertFalse(after.get(1).current());
    }

    @Test
    @DisplayName("回滚不存在的版本直接拒绝，定义不变")
    void rollbackRejectsMissingVersion() {
        repository.save(agent("agent-a", "当前"), "admin", null);
        String current = repository.load().revision();

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> repository.rollback("agent-a", 9, current, "admin"));

        assertTrue(error.getMessage().contains("没有版本 v9"), error.getMessage());
        assertEquals("当前", repository.find("agent-a").orElseThrow().name());
    }

    @Test
    @DisplayName("回滚携带过期 revision 返回 409，不覆盖新内容")
    void rollbackChecksRevision() {
        repository.save(agent("agent-a", "v1"), "admin", null, "创建");
        String stale = repository.load().revision();
        repository.save(agent("agent-a", "v2"), "admin", stale, "改名");

        assertThrows(
                PayrollConflictException.class,
                () -> repository.rollback("agent-a", 1, stale, "admin"));
        assertEquals("v2", repository.find("agent-a").orElseThrow().name());
    }

    @Test
    @DisplayName("删除定义同时清理版本历史，重建同 id 不会串出旧版本")
    void deleteClearsVersions() {
        repository.save(agent("agent-a", "旧"), "admin", null);
        String current = repository.load().revision();

        assertTrue(repository.delete("agent-a", current));
        assertTrue(repository.versions("agent-a").isEmpty());
    }

    @Test
    @DisplayName("空表导入只生效一次，重复执行不产生重复行")
    void importIfEmptyIsIdempotent() {
        List<AgentDefinition> agents =
                List.of(agent("agent-a", "个税顾问"), agent("agent-b", "社保顾问"));

        assertEquals(2, repository.importIfEmpty(agents, "nacos-import"));
        assertEquals(0, repository.importIfEmpty(agents, "nacos-import"));
        assertEquals(2, repository.load().items().size());
        assertEquals("社保顾问", repository.find("agent-b").orElseThrow().name());
    }

    @Test
    @DisplayName("导入前已有数据时直接跳过，不覆盖现有定义")
    void importSkipsWhenTableNotEmpty() {
        repository.save(agent("agent-a", "人工创建"), "admin", null);

        assertEquals(
                0,
                repository.importIfEmpty(List.of(agent("agent-a", "Nacos 版本")), "nacos-import"));
        assertEquals("人工创建", repository.find("agent-a").orElseThrow().name());
    }

    private boolean updateWith(CountDownLatch start, String id, String name, String expected)
            throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            repository.save(agent(id, name), "tester", expected);
            return true;
        } catch (PayrollConflictException e) {
            return false;
        }
    }

    private long rowRevision(String id) throws Exception {
        try (var connection = database.connection();
                var statement =
                        connection.prepareStatement(
                                "SELECT revision FROM agent_definition WHERE id = ?")) {
            statement.setString(1, id);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static AgentDefinition agent(String id, String name) {
        return new AgentDefinition(
                id,
                name,
                "🧾",
                "负责个税测算",
                List.of("税务"),
                List.of("calculator"),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "admin",
                LocalDateTime.of(2026, 11, 20, 9, 30, 15),
                LocalDateTime.of(2026, 11, 20, 9, 30, 15),
                List.of(AgentDefinition.CAPABILITY_CALC));
    }
}
