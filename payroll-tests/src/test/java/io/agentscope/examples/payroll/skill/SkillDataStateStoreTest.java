package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V24 两张读模型表：数据状态快照与模板创建血缘的库实现。 */
class SkillDataStateStoreTest {

    private PayrollDatabase database;
    private JdbcSkillDataStateStore states;
    private JdbcSkillCreationOriginStore origins;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("skill-console-state-" + System.nanoTime());
        states = new JdbcSkillDataStateStore(database);
        origins = new JdbcSkillCreationOriginStore(database);
    }

    @Test
    @DisplayName("写入后可读回全部字段，exitCode 为空也能往返")
    void roundTripsNullableExitCode() {
        states.upsert(
                new SkillDataState(
                        "poetry-skill", SkillDataState.READY, 4096L, 0, 1_000L, 2_000L,
                        "初始化完成", 3_000L));
        states.upsert(
                new SkillDataState(
                        "calc-skill", SkillDataState.UNKNOWN, 0L, null, 0L, 0L, "", 4_000L));

        SkillDataState ready = states.find("poetry-skill").orElseThrow();
        assertEquals(SkillDataState.READY, ready.status());
        assertEquals(4096L, ready.sizeBytes());
        assertEquals(0, ready.exitCode());
        assertEquals(1_000L, ready.startedAt());
        assertEquals(2_000L, ready.finishedAt());
        assertEquals("初始化完成", ready.message());
        assertEquals(3_000L, ready.observedAt());

        assertNull(states.find("calc-skill").orElseThrow().exitCode());
        assertEquals(2, states.findAll().size());
    }

    @Test
    @DisplayName("同一技能重复写入是 upsert，不产生第二行")
    void upsertReplacesExistingRow() {
        states.upsert(
                new SkillDataState(
                        "poetry-skill", SkillDataState.INITIALIZING, 0L, null, 1_000L, 0L, "",
                        1_000L));
        states.upsert(
                new SkillDataState(
                        "poetry-skill", SkillDataState.READY, 512L, 0, 1_000L, 2_000L, "done",
                        3_000L));

        Map<String, SkillDataState> all = states.findAll();
        assertEquals(1, all.size());
        assertEquals(SkillDataState.READY, all.get("poetry-skill").status());
        assertEquals(512L, all.get("poetry-skill").sizeBytes());
    }

    @Test
    @DisplayName("删除后读不到该技能")
    void deleteRemovesRow() {
        states.upsert(SkillDataState.unknown("poetry-skill", 1_000L));

        states.delete("poetry-skill");

        assertTrue(states.find("poetry-skill").isEmpty());
    }

    @Test
    @DisplayName("待刷新只取非终态且观测早于截止时间，按观测时间升序并限流")
    void pendingBeforeFiltersNonTerminalAndOldRows() {
        states.upsert(
                new SkillDataState(
                        "initializing", SkillDataState.INITIALIZING, 0L, null, 0, 0, "", 1_000L));
        states.upsert(
                new SkillDataState(
                        "busy", SkillDataState.BUSY, 0L, null, 0, 0, "", 5_000L));
        states.upsert(
                new SkillDataState(
                        "ready", SkillDataState.READY, 0L, 0, 0, 0, "", 1_000L));
        states.upsert(
                new SkillDataState(
                        "failed", SkillDataState.FAILED, 0L, 1, 0, 0, "", 1_000L));

        List<SkillDataState> due = states.pendingBefore(4_000L, 10);
        assertEquals(List.of("initializing"), due.stream().map(SkillDataState::skillId).toList());

        List<SkillDataState> capped = states.pendingBefore(6_000L, 1);
        assertEquals(1, capped.size());
        assertEquals("initializing", capped.get(0).skillId());
    }

    @Test
    @DisplayName("模板来源记录可按技能读回，重复记录覆盖模板而不新增行")
    void originRecordsAndOverwrites() {
        origins.record("policy-check", "policy-note", "hr01");
        SkillCreationOriginStore.Origin first = origins.origins().get("policy-check");
        assertEquals("policy-note", first.templateId());
        assertEquals("hr01", first.operator());
        assertTrue(first.createdAt() > 0);

        origins.record("policy-check", "kb-qa-assistant", "hr02");
        Map<String, SkillCreationOriginStore.Origin> all = origins.origins();
        assertEquals(1, all.size());
        assertEquals("kb-qa-assistant", all.get("policy-check").templateId());
        assertEquals("hr02", all.get("policy-check").operator());

        origins.delete("policy-check");
        assertTrue(origins.origins().isEmpty());
    }

    @Test
    @DisplayName("空白操作者落为空串而不是编一个名字")
    void blankOperatorStoredEmpty() {
        origins.record("policy-check", "policy-note", "   ");

        assertEquals("", origins.origins().get("policy-check").operator());
    }
}
