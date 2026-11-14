package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 入库任务队列：创建、查询、取消、重试与全量重建。 */
@Service
public class KbTaskService {

    private final KbTaskStore taskStore;
    private final KbDocumentStore documentStore;
    private final KbSeriesService seriesService;
    private final KbPermissionService permissionService;
    private final KbIngestService ingestService;
    private final H2AdminRepository adminRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public KbTaskService(
            KbTaskStore taskStore,
            KbDocumentStore documentStore,
            KbSeriesService seriesService,
            KbPermissionService permissionService,
            KbIngestService ingestService,
            H2AdminRepository adminRepository,
            AuditService auditService,
            ObjectMapper objectMapper,
            @Qualifier("kbIngestExecutor") ExecutorService executor) {
        this.taskStore = taskStore;
        this.documentStore = documentStore;
        this.seriesService = seriesService;
        this.permissionService = permissionService;
        this.ingestService = ingestService;
        this.adminRepository = adminRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.executor = executor;
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
        executor.submit(() -> ingestService.run(task.id()));
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
            executor.submit(() -> ingestService.run(task.id()));
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
        executor.submit(() -> ingestService.run(created.id()));
        auditService.record(
                "kb.task.retry", "kb_ingest_task", String.valueOf(taskId), task.status(), created.id());
        return view(created);
    }

    private IngestTask insert(
            long seriesId,
            Long documentId,
            TaskType type,
            Long configVersionId,
            AuthUser user,
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
                        user.id(),
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
        Map<Long, String> names = new HashMap<>();
        for (IngestTask task : tasks) {
            if (task.documentId() != null && !names.containsKey(task.documentId())) {
                documentStore
                        .findById(task.documentId())
                        .ifPresent(document -> names.put(document.id(), document.name()));
            }
        }
        return names;
    }

    private Map<Long, String> operatorNames(List<IngestTask> tasks) {
        Map<Long, String> names = new HashMap<>();
        for (IngestTask task : tasks) {
            if (!names.containsKey(task.operatorId())) {
                names.put(
                        task.operatorId(),
                        task.operatorId() == 0
                                ? "系统管理员"
                                : adminRepository
                                        .findUser(task.operatorId())
                                        .map(AppUser::displayName)
                                        .orElse("用户#" + task.operatorId()));
            }
        }
        return names;
    }
}
