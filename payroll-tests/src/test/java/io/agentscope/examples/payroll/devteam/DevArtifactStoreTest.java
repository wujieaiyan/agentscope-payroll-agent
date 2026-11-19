package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 产物库的版本递增、补丁 hash、同内容去重与非法输入边界。 */
class DevArtifactStoreTest {

    private DevArtifactStore store;

    @BeforeEach
    void setUp() {
        PayrollDatabase database =
                PayrollDatabase.inMemory("dev-artifact-" + System.nanoTime());
        store = new DevArtifactStore(database);
    }

    @Test
    @DisplayName("同一任务的同一类型版本递增，不同类型各自从 1 开始")
    void versionsIncrementPerTaskAndType() {
        DevArtifact first =
                store.save(
                        "task-1",
                        DevArtifactType.REQUIREMENTS,
                        "project-product",
                        "需求分析",
                        "# 需求 v1",
                        null);
        DevArtifact second =
                store.save(
                        "task-1",
                        DevArtifactType.REQUIREMENTS,
                        "project-product",
                        "需求分析",
                        "# 需求 v2",
                        null);
        DevArtifact architecture =
                store.save(
                        "task-1",
                        DevArtifactType.ARCHITECTURE,
                        "project-architect",
                        "",
                        "# 架构",
                        null);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals(1, architecture.version());
        assertEquals("architecture.md", architecture.title());
        assertEquals(2, store.history("task-1", DevArtifactType.REQUIREMENTS).size());
        assertEquals("# 需求 v2", store.latest("task-1", DevArtifactType.REQUIREMENTS).orElseThrow().content());
        assertEquals(3, store.list("task-1").size());
    }

    @Test
    @DisplayName("同内容重复提交不产生新版本")
    void identicalContentIsIdempotent() {
        DevArtifact first =
                store.save(
                        "task-2", DevArtifactType.TASKS, "project-manager", "", "{\"tasks\":[]}", null);
        DevArtifact again =
                store.save(
                        "task-2", DevArtifactType.TASKS, "project-manager", "", "{\"tasks\":[]}", null);

        assertEquals(first.artifactId(), again.artifactId());
        assertEquals(1, again.version());
        assertEquals(1, store.history("task-2", DevArtifactType.TASKS).size());
    }

    @Test
    @DisplayName("补丁类产物必带 diff hash，非补丁类型忽略该字段")
    void patchesCarryDiffHash() {
        DevArtifact backend =
                store.save(
                        "task-3",
                        DevArtifactType.PATCH_BACKEND,
                        "project-implementer",
                        "",
                        "--- a/A.java\n+++ b/A.java\n",
                        null);
        DevArtifact frontend =
                store.save(
                        "task-3",
                        DevArtifactType.PATCH_FRONTEND,
                        "project-frontend",
                        "",
                        "--- a/App.vue\n+++ b/App.vue\n",
                        "manual-hash");
        DevArtifact report =
                store.save(
                        "task-3",
                        DevArtifactType.TEST_REPORT,
                        "project-qa",
                        "",
                        "全部通过",
                        "ignored");

        assertEquals(backend.contentHash(), backend.diffHash());
        assertEquals(64, backend.diffHash().length());
        assertEquals("manual-hash", frontend.diffHash());
        assertNull(report.diffHash());
        assertTrue(DevArtifactType.PATCH_DB.patch());
        assertFalse(DevArtifactType.TEST_REPORT.patch());
    }

    @Test
    @DisplayName("非法任务 ID、空 owner、空类型与超限内容一律拒绝")
    void rejectsInvalidInput() {
        for (String taskId : new String[] {"", "   ", "../escape", "a/b", "task id"}) {
            assertThrows(
                    PayrollException.class,
                    () ->
                            store.save(
                                    taskId,
                                    DevArtifactType.REQUIREMENTS,
                                    "owner",
                                    "",
                                    "x",
                                    null),
                    taskId);
        }
        assertThrows(
                PayrollException.class,
                () ->
                        store.save(
                                "task-4",
                                DevArtifactType.REQUIREMENTS,
                                " ",
                                "",
                                "x",
                                null));
        assertThrows(
                PayrollException.class,
                () -> store.save("task-4", null, "owner", "", "x", null));
        assertThrows(
                PayrollException.class,
                () ->
                        store.save(
                                "task-4",
                                DevArtifactType.REQUIREMENTS,
                                "owner",
                                "",
                                "a".repeat(DevArtifactStore.MAX_CONTENT_BYTES + 1),
                                null));
    }

    @Test
    @DisplayName("产物按 task_id 隔离，落盘路径包含任务、版本与固定文件名")
    void isolatesTasksAndExposesPath() {
        store.save(
                "task-a", DevArtifactType.REVIEW_REPORT, "project-reviewer", "", "通过", null);
        DevArtifact b =
                store.save(
                        "task-b", DevArtifactType.ROLLBACK, "project-devops", "", "# 回滚", null);

        assertEquals(1, store.list("task-a").size());
        assertEquals(1, store.list("task-b").size());
        assertEquals(0, store.history("task-a", DevArtifactType.ROLLBACK).size());
        assertEquals("dev_tasks/task-b/v1/rollback.md", b.relativePath());
        assertNotEquals(b.artifactId(), store.latest("task-a", DevArtifactType.REVIEW_REPORT).orElseThrow().artifactId());
    }

    @Test
    @DisplayName("产物类型 id 可解析且未知值返回空")
    void parsesArtifactTypes() {
        assertEquals(DevArtifactType.PATCH_DB, DevArtifactType.parse("PATCH-DB").orElseThrow());
        assertEquals(DevArtifactType.TASKS, DevArtifactType.parse(" tasks ").orElseThrow());
        assertTrue(DevArtifactType.parse("unknown").isEmpty());
        assertTrue(DevArtifactType.parse(null).isEmpty());
        assertEquals("requirements", DevArtifactType.REQUIREMENTS.id());
        assertEquals("requirements.md", DevArtifactType.REQUIREMENTS.fileName());
        assertEquals("patch-devops.diff", DevArtifactType.PATCH_DEVOPS.fileName());
    }
}
