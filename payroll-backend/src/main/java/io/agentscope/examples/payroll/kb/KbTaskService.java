package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbDtos.TaskDetailView;
import io.agentscope.examples.payroll.kb.KbDtos.TaskLogView;
import io.agentscope.examples.payroll.kb.KbDtos.TaskView;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbModels.IngestTask;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 入库任务队列：创建、查询、取消、重试、启动恢复与全量重建。 */
@Service
public class KbTaskService {

    private static final Logger log = LoggerFactory.getLogger(KbTaskService.class);

    private final KbTaskStore taskStore;
    private final KbDocumentStore documentStore;
    private final KbSeriesService seriesService;
    private final KbPermissionService permissionService;
    private final KbIngestService ingestService;
    private final H2AdminRepository adminRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final int maxRetries;

    public KbTaskService(
            KbTaskStore taskStore,
            KbDocumentStore documentStore,
            KbSeriesService seriesService,
            KbPermissionService permissionService,
            KbIngestService ingestService,
            H2AdminRepository adminRepository,
            AuditService auditService,
            ObjectMapper objectMapper,
            @Qualifier("kbIngestExecutor") ExecutorService executor,
            @Value("${payroll.kb.task-max-retries:3}") int maxRetries) {
        this.taskStore = taskStore;
        this.documentStore = documentStore;
        this.seriesService = seriesService;
        this.permissionService = permissionService;
        this.ingestService = ingestService;
        this.adminRepository = adminRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.maxRetries = Math.max(0, maxRetries);
    }

    public TaskView createIngest(
            long seriesId, long documentId, TaskType type, Long configVersionId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Document document =
                documentStore
                        .findById(documentId, seriesId)
                        .orElseThrow(() -> new PayrollException("文档不存在"));
        if (document.deletedAt() != null) {
            throw new PayrollException("文档已删除");
        }
        if (taskStore.hasActiveTask(documentId)) {
            throw new PayrollConflictException("该文档已有入库任务在执行，请等待完成");
        }
        IngestTask task = insert(seriesId, documentId, type, configVersionId, user, null, 0);
        documentStore.updateStatus(documentId, DocStatus.PENDING, null, null);
        submit(task.id());
        auditService.record(
                "kb.task.create", "kb_ingest_task", String.valueOf(task.id()), null, task.type());
        return view(task);
    }

    public List<TaskView> rebuild(
            long seriesId, String mode, List<Long> documentIds, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        List<Document> documents;
        if ("FULL".equals(normalized)) {
            documents = documentStore.findBySeries(seriesId, null, null, null);
        } else if ("FAILED".equals(normalized)) {
            documents = documentStore.findBySeries(seriesId, null, DocStatus.FAILED, null);
        } else if ("DOCUMENT".equals(normalized)) {
            if (documentIds == null || documentIds.isEmpty()) {
                throw new PayrollException("请选择要重建的文档");
            }
            documents = documentStore.findByIds(seriesId, documentIds);
        } else {
            throw new PayrollException("重建模式仅支持 FULL/FAILED/DOCUMENT");
        }
        List<TaskView> tasks = new ArrayList<>();
        for (Document document : documents) {
            if (taskStore.hasActiveTask(document.id())) {
                continue;
            }
            IngestTask task =
                    insert(seriesId, document.id(), TaskType.REBUILD, null, user, null, 0);
            documentStore.updateStatus(document.id(), DocStatus.PENDING, null, null);
            submit(task.id());
            tasks.add(view(task));
        }
        auditService.record(
                "kb.task.rebuild",
                "kb_series",
                String.valueOf(seriesId),
                null,
                Map.of("mode", normalized, "tasks", tasks.size()));
        seriesService.refreshCounters(seriesId);
        return tasks;
    }

    public List<TaskView> list(
            long seriesId, String status, String type, int limit, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        TaskStatus statusFilter = status == null || status.isBlank() ? null : TaskStatus.parse(status, null);
        TaskType typeFilter = type == null || type.isBlank() ? null : TaskType.parse(type, null);
        List<IngestTask> tasks = taskStore.findBySeries(seriesId, statusFilter, typeFilter, limit);
        Map<Long, String> names = documentNames(tasks);
        Map<Long, String> operators = operatorNames(tasks);
        return tasks.stream().map(task -> view(task, names, operators)).toList();
    }

    public TaskDetailView detail(long seriesId, long taskId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        IngestTask task = requireTask(seriesId, taskId);
        return new TaskDetailView(
                view(task),
                taskStore.findLogs(taskId, 500).stream()
                        .map(
                                entry ->
                                        new TaskLogView(
                                                entry.id(),
                                                entry.stage(),
                                                entry.level(),
                                                entry.message(),
                                                entry.costMs(),
                                                entry.createdAt()))
                        .toList());
    }

    public TaskView cancel(long seriesId, long taskId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        IngestTask task = requireTask(seriesId, taskId);
        if (task.status() == TaskStatus.SUCCEEDED
                || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELED) {
            throw new PayrollException("任务已结束，无法取消");
        }
        taskStore.requestCancel(taskId);
        if (task.status() == TaskStatus.QUEUED) {
            taskStore.markCanceled(taskId);
        }
        auditService.record("kb.task.cancel", "kb_ingest_task", String.valueOf(taskId), task.status(), "CANCELED");
        return view(taskStore.findById(taskId).orElseThrow());
    }

    public TaskView retry(long seriesId, long taskId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        IngestTask task = requireTask(seriesId, taskId);
        if (task.status() != TaskStatus.FAILED && task.status() != TaskStatus.CANCELED) {
            throw new PayrollException("仅失败或已取消的任务可以重试");
        }
        if (task.retryCount() >= maxRetries) {
            throw new PayrollException(
                    "已达到最大重试次数（" + maxRetries + " 次），请排查失败原因后重新导入文档");
        }
        if (task.documentId() == null) {
            throw new PayrollException("任务缺少文档信息");
        }
        if (taskStore.hasActiveTask(task.documentId())) {
            throw new PayrollConflictException("该文档已有入库任务在执行");
        }
        IngestTask created =
                insert(
                        seriesId,
                        task.documentId(),
                        task.type(),
                        task.configVersionId(),
                        user,
                        task.id(),
                        task.retryCount() + 1);
        documentStore.updateStatus(task.documentId(), DocStatus.PENDING, null, null);
        submit(created.id());
        auditService.record(
                "kb.task.retry", "kb_ingest_task", String.valueOf(taskId), task.status(), created.id());
        return view(created);
    }

    /**
     * 启动恢复：把上一实例遗留的未完成任务收敛到可继续状态。
     *
     * <p>同一文档存在多个活动任务时只保留任务号最大的一个；CANCELING 直接终态化；QUEUED
     * 重新排队；RUNNING 因进程中断置为失败，未达重试上限时自动重建后续任务。
     */
    public RecoverySummary recoverInterrupted() {
        List<IngestTask> actives = taskStore.findActiveTasks(2000);
        if (actives.isEmpty()) {
            return new RecoverySummary(0, 0, 0, 0, 0);
        }
        Map<Long, List<IngestTask>> byDocument = new LinkedHashMap<>();
        List<IngestTask> orphan = new ArrayList<>();
        for (IngestTask task : actives) {
            if (task.documentId() == null) {
                orphan.add(task);
            } else {
                byDocument.computeIfAbsent(task.documentId(), key -> new ArrayList<>()).add(task);
            }
        }
        int queued = 0;
        int retried = 0;
        int exhausted = 0;
        int canceled = 0;
        int failed = 0;
        Set<Long> seriesTouched = new HashSet<>();
        for (IngestTask task : orphan) {
            taskStore.markFailed(task.id(), "DOCUMENT_MISSING", "任务缺少文档信息，启动恢复时终止", null);
            failed++;
        }
        for (Map.Entry<Long, List<IngestTask>> entry : byDocument.entrySet()) {
            List<IngestTask> tasks = entry.getValue();
            IngestTask current = tasks.get(tasks.size() - 1);
            for (int i = 0; i < tasks.size() - 1; i++) {
                taskStore.markFailed(
                        tasks.get(i).id(),
                        "DUPLICATE_TASK",
                        "同一文档存在更新的入库任务，启动恢复时终止该重复任务",
                        null);
                failed++;
            }
            try {
                Document document = documentStore.findById(entry.getKey()).orElse(null);
                if (document == null || document.deletedAt() != null) {
                    taskStore.markFailed(current.id(), "DOCUMENT_MISSING", "文档不存在或已删除", null);
                    failed++;
                    continue;
                }
                seriesTouched.add(document.seriesId());
                switch (current.status()) {
                    case CANCELING -> {
                        taskStore.markCanceled(current.id());
                        ingestService.cleanupArtifacts(document.id());
                        documentStore.updateStatus(
                                document.id(), DocStatus.PENDING, null, "入库任务已取消");
                        canceled++;
                    }
                    case QUEUED -> {
                        documentStore.updateStatus(document.id(), DocStatus.PENDING, null, null);
                        submit(current.id());
                        queued++;
                    }
                    case RUNNING -> {
                        taskStore.markFailed(
                                current.id(), "SERVICE_RESTART", "服务重启导致入库中断", null);
                        ingestService.cleanupArtifacts(document.id());
                        if (current.retryCount() < maxRetries) {
                            IngestTask next =
                                    insert(
                                            document.seriesId(),
                                            document.id(),
                                            current.type(),
                                            current.configVersionId(),
                                            current.operatorId(),
                                            current.id(),
                                            current.retryCount() + 1);
                            documentStore.updateStatus(
                                    document.id(), DocStatus.PENDING, null, null);
                            submit(next.id());
                            retried++;
                        } else {
                            documentStore.updateStatus(
                                    document.id(),
                                    DocStatus.FAILED,
                                    "RECOVERY_EXHAUSTED",
                                    "服务重启后自动重试已达上限（"
                                            + maxRetries
                                            + " 次），请人工排查后重新导入");
                            exhausted++;
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[知识库] 启动恢复任务失败：task={}，document={}，{}",
                        current.id(),
                        entry.getKey(),
                        e.getMessage());
                failed++;
            }
        }
        for (long seriesId : seriesTouched) {
            try {
                seriesService.refreshCounters(seriesId);
            } catch (RuntimeException e) {
                log.warn("[知识库] 刷新知识库计数失败：series={}，{}", seriesId, e.getMessage());
            }
        }
        RecoverySummary summary = new RecoverySummary(queued, retried, exhausted, canceled, failed);
        if (summary.total() > 0) {
            auditService.record(
                    "kb.task.recover",
                    "kb_ingest_task",
                    "startup",
                    null,
                    Map.of(
                            "queued", queued,
                            "retried", retried,
                            "exhausted", exhausted,
                            "canceled", canceled,
                            "failed", failed));
            log.info(
                    "[知识库] 启动恢复完成：重新排队 {}，自动重试 {}，达到上限 {}，取消 {}，终止 {}",
                    queued,
                    retried,
                    exhausted,
                    canceled,
                    failed);
        }
        return summary;
    }

    /** 启动恢复结果计数，供日志与测试断言。 */
    public record RecoverySummary(
            int queued, int retried, int exhausted, int canceled, int failed) {
        public int total() {
            return queued + retried + exhausted + canceled + failed;
        }
    }

    /** 入库有界队列满时任务落为失败并对外返回 429，避免无限排队。 */
    private void submit(long taskId) {
        try {
            executor.submit(() -> ingestService.run(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("[知识库] 入库队列已满，拒绝任务：task={}", taskId);
            taskStore.markFailed(taskId, "QUEUE_FULL", "入库队列已满，请稍后重试", null);
            IngestTask task = taskStore.findById(taskId).orElse(null);
            if (task != null) {
                if (task.documentId() != null) {
                    documentStore.updateStatus(
                            task.documentId(),
                            DocStatus.FAILED,
                            "QUEUE_FULL",
                            "入库队列已满，请稍后重试");
                }
                seriesService.refreshCounters(task.seriesId());
            }
            throw new TooManyRequestsException("入库队列已满，请稍后重试");
        }
    }

    private IngestTask insert(
            long seriesId,
            Long documentId,
            TaskType type,
            Long configVersionId,
            AuthUser user,
            Long retryOf,
            int retryCount) {
        return insert(seriesId, documentId, type, configVersionId, user.id(), retryOf, retryCount);
    }

    private IngestTask insert(
            long seriesId,
            Long documentId,
            TaskType type,
            Long configVersionId,
            long operatorId,
            Long retryOf,
            int retryCount) {
        String payload;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("documentId", documentId);
            payload = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            payload = "{}";
        }
        return taskStore.insert(
                new IngestTask(
                        0L,
                        seriesId,
                        documentId,
                        type,
                        TaskStatus.QUEUED,
                        0,
                        "QUEUED",
                        retryCount,
                        retryOf,
                        configVersionId,
                        UUID.randomUUID().toString(),
                        payload,
                        null,
                        null,
                        null,
                        operatorId,
                        null,
                        null,
                        null,
                        null));
    }

    private IngestTask requireTask(long seriesId, long taskId) {
        IngestTask task =
                taskStore.findById(taskId).orElseThrow(() -> new PayrollException("任务不存在"));
        if (task.seriesId() != seriesId) {
            throw new PayrollException("任务不属于该知识库");
        }
        return task;
    }

    public TaskView view(IngestTask task) {
        Map<Long, String> names = new HashMap<>();
        if (task.documentId() != null) {
            documentStore.findById(task.documentId()).ifPresent(doc -> names.put(doc.id(), doc.name()));
        }
        Map<Long, String> operators = operatorNames(List.of(task));
        return view(task, names, operators);
    }

    private TaskView view(IngestTask task, Map<Long, String> names, Map<Long, String> operators) {
        return new TaskView(
                task.id(),
                task.seriesId(),
                task.documentId(),
                task.documentId() == null ? null : names.get(task.documentId()),
                task.type(),
                task.status(),
                task.progress(),
                task.stage(),
                task.retryCount(),
                task.retryOfTaskId(),
                task.errorCode(),
                task.errorMessage(),
                task.operatorId(),
                operators.getOrDefault(task.operatorId(), "用户#" + task.operatorId()),
                task.startedAt(),
                task.finishedAt(),
                task.createdAt(),
                task.updatedAt());
    }

    private Map<Long, String> documentNames(List<IngestTask> tasks) {
        Map<Long, Set<Long>> idsBySeries = new HashMap<>();
        for (IngestTask task : tasks) {
            if (task.documentId() != null) {
                idsBySeries
                        .computeIfAbsent(task.seriesId(), key -> new LinkedHashSet<>())
                        .add(task.documentId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        idsBySeries.forEach(
                (seriesId, ids) ->
                        documentStore
                                .findByIds(seriesId, ids)
                                .forEach(document -> names.put(document.id(), document.name())));
        return names;
    }

    private Map<Long, String> operatorNames(List<IngestTask> tasks) {
        Map<Long, String> names = new HashMap<>();
        Set<Long> pending = new LinkedHashSet<>();
        for (IngestTask task : tasks) {
            if (task.operatorId() == 0) {
                names.put(0L, "系统管理员");
            } else {
                pending.add(task.operatorId());
            }
        }
        if (!pending.isEmpty()) {
            adminRepository
                    .findUsers(pending)
                    .forEach(user -> names.put(user.id(), user.displayName()));
            for (Long id : pending) {
                names.putIfAbsent(id, "用户#" + id);
            }
        }
        return names;
    }
}
