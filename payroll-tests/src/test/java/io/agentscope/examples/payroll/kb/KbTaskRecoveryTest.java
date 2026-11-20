package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbModels.IngestTask;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbTaskRecoveryTest {

    private static final long SERIES_ID = 1L;
    private static final AuthUser USER =
            new AuthUser(7L, "kbowner", "库管理员", "ADMIN", "管理员", null, null, Set.of());

    private PayrollDatabase database;
    private KbTaskStore taskStore;
    private KbDocumentStore documentStore;
    private RecordingExecutor executor;
    private KbIngestService ingestService;
    private KbTaskService service;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("kb-recovery-" + UUID.randomUUID());
        taskStore = new KbTaskStore(database);
        documentStore = new KbDocumentStore(database);
        executor = new RecordingExecutor();
        ingestService = mock(KbIngestService.class);
        service =
                new KbTaskService(
                        taskStore,
                        documentStore,
                        mock(KbSeriesService.class),
                        mock(KbPermissionService.class),
                        ingestService,
                        new H2AdminRepository(database),
                        mock(AuditService.class),
                        new ObjectMapper(),
                        executor,
                        3);
    }

    @Test
    @DisplayName("重启遗留的 RUNNING 任务置失败并自动生成后续任务")
    void runningTaskIsRetriedAutomatically() {
        long documentId = seedDocument("handbook.pdf");
        long original = seedTask(documentId, TaskStatus.RUNNING, 0);

        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(1, summary.retried());
        assertEquals(1, executor.submitted.size());
        IngestTask failed = taskStore.findById(original).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("SERVICE_RESTART", failed.errorCode());

        IngestTask next = otherTask(documentId, original);
        assertEquals(TaskStatus.QUEUED, next.status());
        assertEquals(1, next.retryCount());
        assertEquals(original, next.retryOfTaskId());
        assertEquals(7L, next.operatorId());
        assertEquals(DocStatus.PENDING, document(documentId).status());

        executor.submitted.get(0).run();
        verify(ingestService).run(next.id());
        verify(ingestService).cleanupArtifacts(documentId);
    }

    @Test
    @DisplayName("达到重试上限的中断任务不再重建，文档落失败等待人工处理")
    void runningTaskAtRetryLimitIsNotRetried() {
        long documentId = seedDocument("handbook.pdf");
        long original = seedTask(documentId, TaskStatus.RUNNING, 3);

        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(1, summary.exhausted());
        assertEquals(0, summary.retried());
        assertTrue(executor.submitted.isEmpty());
        assertEquals(TaskStatus.FAILED, taskStore.findById(original).orElseThrow().status());
        Document document = document(documentId);
        assertEquals(DocStatus.FAILED, document.status());
        assertEquals("RECOVERY_EXHAUSTED", document.errorCode());
        assertNull(otherTask(documentId, original));
        verify(ingestService).cleanupArtifacts(documentId);
    }

    @Test
    @DisplayName("重启前仍在排队的任务原样重新提交")
    void queuedTaskIsResubmitted() {
        long documentId = seedDocument("policy.pdf");
        long queued = seedTask(documentId, TaskStatus.QUEUED, 0);

        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(1, summary.queued());
        assertEquals(1, executor.submitted.size());
        assertEquals(TaskStatus.QUEUED, taskStore.findById(queued).orElseThrow().status());
        assertEquals(DocStatus.PENDING, document(documentId).status());

        executor.submitted.get(0).run();
        verify(ingestService).run(queued);
    }

    @Test
    @DisplayName("取消中的任务重启后直接终态化，文档回到待入库")
    void cancelingTaskIsFinalized() {
        long documentId = seedDocument("contract.pdf");
        long canceling = seedTask(documentId, TaskStatus.CANCELING, 0);

        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(1, summary.canceled());
        assertTrue(executor.submitted.isEmpty());
        assertEquals(TaskStatus.CANCELED, taskStore.findById(canceling).orElseThrow().status());
        Document document = document(documentId);
        assertEquals(DocStatus.PENDING, document.status());
        assertEquals("入库任务已取消", document.errorMessage());
        verify(ingestService).cleanupArtifacts(documentId);
    }

    @Test
    @DisplayName("同一文档存在多个活动任务时只保留最新任务")
    void duplicateActiveTasksKeepNewest() {
        long documentId = seedDocument("handbook.pdf");
        long stale = seedTask(documentId, TaskStatus.RUNNING, 0);
        long latest = seedTask(documentId, TaskStatus.RUNNING, 0);

        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(1, summary.failed());
        assertEquals(1, summary.retried());
        IngestTask duplicate = taskStore.findById(stale).orElseThrow();
        assertEquals(TaskStatus.FAILED, duplicate.status());
        assertEquals("DUPLICATE_TASK", duplicate.errorCode());
        assertEquals(TaskStatus.FAILED, taskStore.findById(latest).orElseThrow().status());

        IngestTask next = otherTask(documentId, latest);
        assertEquals(latest, next.retryOfTaskId());
        assertEquals(3, taskStore.findByDocument(documentId, 10).size());
    }

    @Test
    @DisplayName("手动重试达到上限时拒绝创建后续任务")
    void manualRetryRejectsAtLimit() {
        long documentId = seedDocument("policy.pdf");
        long failed = seedTask(documentId, TaskStatus.FAILED, 3);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.retry(SERIES_ID, failed, USER));

        assertTrue(error.getMessage().contains("最大重试次数"));
        assertTrue(executor.submitted.isEmpty());
        assertEquals(1, taskStore.findByDocument(documentId, 10).size());
    }

    @Test
    @DisplayName("手动重试未达上限时创建下一级任务并重新排队")
    void manualRetryBelowLimitCreatesNextTask() {
        long documentId = seedDocument("policy.pdf");
        long failed = seedTask(documentId, TaskStatus.FAILED, 2);

        service.retry(SERIES_ID, failed, USER);

        assertEquals(1, executor.submitted.size());
        IngestTask next = otherTask(documentId, failed);
        assertEquals(TaskStatus.QUEUED, next.status());
        assertEquals(3, next.retryCount());
        assertEquals(failed, next.retryOfTaskId());
        assertEquals(DocStatus.PENDING, document(documentId).status());
        verify(ingestService, never()).run(failed);
    }

    @Test
    @DisplayName("没有遗留任务时恢复不产生任何写入")
    void emptyDatabaseRecoversNothing() {
        KbTaskService.RecoverySummary summary = service.recoverInterrupted();

        assertEquals(0, summary.total());
        assertTrue(executor.submitted.isEmpty());
    }

    private long seedDocument(String name) {
        Document document =
                new Document(
                        0L,
                        SERIES_ID,
                        0L,
                        name,
                        "pdf",
                        "application/pdf",
                        1024L,
                        "sha-" + name,
                        "kb/2026/" + name,
                        null,
                        null,
                        DocStatus.PARSING,
                        null,
                        null,
                        0,
                        null,
                        7L,
                        1L,
                        null,
                        null,
                        null);
        return documentStore.insert(document).id();
    }

    private long seedTask(long documentId, TaskStatus status, int retryCount) {
        IngestTask task =
                new IngestTask(
                        0L,
                        SERIES_ID,
                        documentId,
                        TaskType.INGEST,
                        status,
                        10,
                        status.name(),
                        retryCount,
                        null,
                        null,
                        UUID.randomUUID().toString(),
                        "{}",
                        null,
                        null,
                        null,
                        7L,
                        null,
                        null,
                        null,
                        null);
        return taskStore.insert(task).id();
    }

    private Document document(long documentId) {
        return documentStore.findById(documentId).orElseThrow();
    }

    /** 返回同一文档下除给定任务外任务号最大的任务；没有时返回 null，便于断言未重建。 */
    private IngestTask otherTask(long documentId, long excludedTaskId) {
        return taskStore.findByDocument(documentId, 10).stream()
                .filter(task -> task.id() != excludedTaskId)
                .max(Comparator.comparingLong(IngestTask::id))
                .orElse(null);
    }

    private static final class RecordingExecutor extends AbstractExecutorService {

        private final List<Runnable> submitted = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            submitted.add(command);
        }
    }
}
