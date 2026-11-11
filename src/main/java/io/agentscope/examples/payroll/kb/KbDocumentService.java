package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbConfigModels.ParseConfig;
import io.agentscope.examples.payroll.kb.KbDtos.BatchDocumentRequest;
import io.agentscope.examples.payroll.kb.KbDtos.BatchDocumentResult;
import io.agentscope.examples.payroll.kb.KbDtos.ChunkView;
import io.agentscope.examples.payroll.kb.KbDtos.DocumentStatusSummary;
import io.agentscope.examples.payroll.kb.KbDtos.DocumentView;
import io.agentscope.examples.payroll.kb.KbDtos.TagView;
import io.agentscope.examples.payroll.kb.KbDtos.UpdateDocumentRequest;
import io.agentscope.examples.payroll.kb.KbDtos.UploadResult;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import io.agentscope.examples.payroll.kb.KbModels.Directory;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbModels.Tag;
import io.agentscope.examples.payroll.service.AuditService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 文档上传、去重、列表、标签与生命周期操作。 */
@Service
public class KbDocumentService {

    private final KbDocumentStore documentStore;
    private final KbChunkStore chunkStore;
    private final KbDirectoryStore directoryStore;
    private final KbTagStore tagStore;
    private final KbTaskStore taskStore;
    private final KbTaskService taskService;
    private final KbSeriesService seriesService;
    private final KbConfigService configService;
    private final KbStorageService storageService;
    private final KbElasticsearchService elasticsearchService;
    private final KbPermissionService permissionService;
    private final H2AdminRepository adminRepository;
    private final AuditService auditService;
    private final int maxFileSize;
    private final int maxBatch;

    public KbDocumentService(
            KbDocumentStore documentStore,
            KbChunkStore chunkStore,
            KbDirectoryStore directoryStore,
            KbTagStore tagStore,
            KbTaskStore taskStore,
            KbTaskService taskService,
            KbSeriesService seriesService,
            KbConfigService configService,
            KbStorageService storageService,
            KbElasticsearchService elasticsearchService,
            KbPermissionService permissionService,
            H2AdminRepository adminRepository,
            AuditService auditService,
            @Value("${payroll.kb.max-file-size:104857600}") long maxFileSize,
            @Value("${payroll.kb.max-batch-size:50}") int maxBatch) {
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.directoryStore = directoryStore;
        this.tagStore = tagStore;
        this.taskStore = taskStore;
        this.taskService = taskService;
        this.seriesService = seriesService;
        this.configService = configService;
        this.storageService = storageService;
        this.elasticsearchService = elasticsearchService;
        this.permissionService = permissionService;
        this.adminRepository = adminRepository;
        this.auditService = auditService;
        this.maxFileSize = (int) Math.min(Integer.MAX_VALUE, Math.max(1024, maxFileSize));
        this.maxBatch = Math.max(1, Math.min(maxBatch, 200));
    }

    public record DownloadFile(Path path, String name, String mimeType) {
    }

    public UploadResult upload(
            long seriesId,
            List<MultipartFile> files,
            Long directoryId,
            String dedupeAction,
            AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        if (files == null || files.isEmpty()) {
            throw new PayrollException("请选择要上传的文件");
        }
        if (files.size() > maxBatch) {
            throw new PayrollException("单次最多上传 " + maxBatch + " 个文件");
        }
        ConfigVersion config = configService.requireActiveConfig(seriesId);
        if (config == null) {
            throw new PayrollException("知识库尚未配置解析规则");
        }
        ParseConfig parseConfig = configService.payload(config).parse;
        Set<String> allowedTypes = new HashSet<>(parseConfig.docTypes);
        Directory directory = resolveDirectory(seriesId, directoryId);
        boolean overwrite = "OVERWRITE".equalsIgnoreCase(dedupeAction);
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        List<DocumentView> documents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
            try {
                if (file.isEmpty()) {
                    failed++;
                    messages.add(filename + "：文件为空");
                    continue;
                }
                if (file.getSize() > maxFileSize) {
                    failed++;
                    messages.add(filename + "：超过单文件大小限制");
                    continue;
                }
                String ext = KbStorageService.extension(filename);
                if (!KbParserService.SUPPORTED.contains(ext)) {
                    failed++;
                    messages.add(filename + "：不支持的文档类型");
                    continue;
                }
                String configType = toConfigType(ext);
                if (!allowedTypes.contains(configType)
                        || !parseConfig.perType.getOrDefault(configType, new KbConfigModels.TypeParseConfig()).enabled) {
                    failed++;
                    messages.add(filename + "：当前知识库未启用 " + configType + " 解析");
                    continue;
                }
                KbStorageService.StoredFile stored = storageService.store(seriesId, file);
                var existing = documentStore.findBySha(seriesId, stored.sha256());
                if (existing.isPresent()) {
                    if (!overwrite) {
                        skipped++;
                        messages.add(filename + "：内容重复，已跳过（" + existing.get().name() + "）");
                        storageService.deleteQuietly(stored.storageKey());
                        continue;
                    }
                    deleteInternal(seriesId, existing.get());
                }
                Document created =
                        documentStore.insert(
                                new Document(
                                        0L,
                                        seriesId,
                                        directory.id(),
                                        filename,
                                        ext,
                                        file.getContentType() == null ? mimeType(ext) : file.getContentType(),
                                        stored.sizeBytes(),
                                        stored.sha256(),
                                        stored.storageKey(),
                                        null,
                                        null,
                                        DocStatus.PENDING,
                                        null,
                                        null,
                                        0,
                                        config.id(),
                                        user.id(),
                                        1L,
                                        null,
                                        null,
                                        null));
                taskService.createIngest(seriesId, created.id(), TaskType.INGEST, config.id(), user);
                uploaded++;
                documents.add(view(seriesId, documentStore.findById(created.id()).orElse(created)));
            } catch (RuntimeException e) {
                failed++;
                messages.add(filename + "：" + e.getMessage());
            }
        }
        seriesService.refreshCounters(seriesId);
        UploadResult result = new UploadResult(uploaded, skipped, failed, documents, messages);
        auditService.record(
                "kb.document.upload",
                "kb_series",
                String.valueOf(seriesId),
                null,
                Map.of("uploaded", uploaded, "skipped", skipped, "failed", failed));
        return result;
    }

    public List<DocumentView> list(
            long seriesId, Long directoryId, String status, String keyword, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        DocStatus statusFilter =
                status == null || status.isBlank() ? null : DocStatus.parse(status, null);
        List<Document> documents = documentStore.findBySeries(seriesId, directoryId, statusFilter, keyword);
        Map<Long, String> directoryPaths = directoryPaths(seriesId);
        Map<Long, List<Tag>> tags = tagStore.findByDocuments(documents.stream().map(Document::id).toList());
        Map<Long, String> uploaders = uploaderNames(documents);
        return documents.stream()
                .map(document -> view(document, directoryPaths, tags, uploaders))
                .toList();
    }

    public DocumentStatusSummary statusSummary(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        int pending = 0;
        int parsing = 0;
        int chunking = 0;
        int indexing = 0;
        int ready = 0;
        int failed = 0;
        int disabled = 0;
        for (Document document : documentStore.findBySeries(seriesId, null, null, null)) {
            switch (document.status()) {
                case PENDING -> pending++;
                case PARSING -> parsing++;
                case CHUNKING -> chunking++;
                case INDEXING -> indexing++;
                case READY -> ready++;
                case FAILED -> failed++;
                case DISABLED -> disabled++;
                default -> {
                }
            }
        }
        int total = pending + parsing + chunking + indexing + ready + failed + disabled;
        return new DocumentStatusSummary(
                total, pending, parsing, chunking, indexing, ready, failed, disabled);
    }

    public DocumentView get(long seriesId, long documentId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        return view(seriesId, document);
    }

    public DocumentView update(
            long seriesId, long documentId, UpdateDocumentRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        if (request == null) {
            throw new PayrollException("请求内容不能为空");
        }
        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().trim();
            if (name.length() > 255) {
                throw new PayrollException("文档名称不能超过 255 个字符");
            }
            documentStore.updateName(documentId, name);
        }
        if (request.directoryId() != null) {
            resolveDirectory(seriesId, request.directoryId());
            documentStore.move(documentId, request.directoryId());
        }
        DocumentView view = view(seriesId, requireDocument(seriesId, documentId));
        auditService.record(
                "kb.document.update",
                "kb_document",
                String.valueOf(documentId),
                document.name(),
                view.name());
        return view;
    }

    public void delete(long seriesId, long documentId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        if (taskStore.hasActiveTask(documentId)) {
            taskStore.findByDocument(documentId, 1).stream()
                    .findFirst()
                    .ifPresent(task -> taskStore.requestCancel(task.id()));
        }
        deleteInternal(seriesId, document);
        seriesService.refreshCounters(seriesId);
        auditService.record(
                "kb.document.delete",
                "kb_document",
                String.valueOf(documentId),
                document.name(),
                null);
    }

    public Object reparse(long seriesId, long documentId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        requireDocument(seriesId, documentId);
        return taskService.createIngest(seriesId, documentId, TaskType.REPARSE, null, user);
    }

    public DocumentView disable(long seriesId, long documentId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        chunkStore.updateStatusByDocument(documentId, "INACTIVE");
        documentStore.updateStatus(documentId, DocStatus.DISABLED, null, null);
        elasticsearchService.deleteDocument(documentId);
        seriesService.refreshCounters(seriesId);
        DocumentView view = view(seriesId, requireDocument(seriesId, documentId));
        auditService.record(
                "kb.document.disable", "kb_document", String.valueOf(documentId), document.status(), view.status());
        return view;
    }

    public Object enable(long seriesId, long documentId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        long chunks = chunkStore.countByDocument(documentId);
        if (chunks == 0) {
            return taskService.createIngest(seriesId, documentId, TaskType.REPARSE, null, user);
        }
        chunkStore.updateStatusByDocument(documentId, "ACTIVE");
        documentStore.updateStatus(documentId, DocStatus.READY, null, null);
        try {
            elasticsearchService.indexChunks(
                    seriesId,
                    documentId,
                    document.name(),
                    document.ext(),
                    chunkStore.findByDocument(documentId));
        } catch (RuntimeException e) {
            auditService.record(
                    "kb.document.enable",
                    "kb_document",
                    String.valueOf(documentId),
                    null,
                    "ES 重建失败：" + e.getMessage());
        }
        seriesService.refreshCounters(seriesId);
        DocumentView view = view(seriesId, requireDocument(seriesId, documentId));
        auditService.record(
                "kb.document.enable", "kb_document", String.valueOf(documentId), document.status(), view.status());
        return view;
    }

    public Map<String, Object> chunks(
            long seriesId, long documentId, int page, int size, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        requireDocument(seriesId, documentId);
        List<Chunk> all = chunkStore.findByDocument(documentId);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 200));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<ChunkView> items =
                all.subList(from, to).stream()
                        .map(
                                chunk ->
                                        new ChunkView(
                                                chunk.id(),
                                                chunk.seq(),
                                                chunk.level(),
                                                chunk.parentChunkId(),
                                                chunk.content(),
                                                chunk.tokenCount(),
                                                chunk.pageNo(),
                                                chunk.titlePath(),
                                                chunk.status()))
                        .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", all.size());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("parentChunks", all.stream().filter(c -> c.level() == ChunkLevel.PARENT).count());
        return result;
    }

    public List<TagView> setTags(
            long seriesId, long documentId, List<Long> tagIds, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        requireDocument(seriesId, documentId);
        List<Long> distinct = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
        for (Long tagId : distinct) {
            if (tagStore.findById(tagId, seriesId).isEmpty()) {
                throw new PayrollException("标签不存在：" + tagId);
            }
        }
        tagStore.replaceDocumentTags(seriesId, documentId, distinct);
        auditService.record(
                "kb.document.tags",
                "kb_document",
                String.valueOf(documentId),
                null,
                distinct);
        return tagStore.findByDocuments(List.of(documentId)).getOrDefault(documentId, List.of()).stream()
                .map(tag -> new TagView(tag.id(), tag.name(), tag.color(), 0))
                .toList();
    }

    public BatchDocumentResult batch(
            long seriesId, BatchDocumentRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        if (request == null
                || request.action() == null
                || request.documentIds() == null
                || request.documentIds().isEmpty()) {
            throw new PayrollException("请选择要操作的文档");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        int succeeded = 0;
        int failed = 0;
        List<Long> failedIds = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (Long documentId : request.documentIds()) {
            try {
                switch (action) {
                    case "DELETE" -> delete(seriesId, documentId, user);
                    case "REPARSE" -> reparse(seriesId, documentId, user);
                    case "MOVE" -> {
                        if (request.directoryId() == null) {
                            throw new PayrollException("缺少目标目录");
                        }
                        update(
                                seriesId,
                                documentId,
                                new UpdateDocumentRequest(null, request.directoryId()),
                                user);
                    }
                    case "DISABLE" -> disable(seriesId, documentId, user);
                    case "ENABLE" -> enable(seriesId, documentId, user);
                    case "SET_TAGS" -> setTags(seriesId, documentId, request.tagIds(), user);
                    default -> throw new PayrollException("不支持的批量操作：" + action);
                }
                succeeded++;
            } catch (RuntimeException e) {
                failed++;
                failedIds.add(documentId);
                messages.add("文档 " + documentId + "：" + e.getMessage());
            }
        }
        auditService.record(
                "kb.document.batch",
                "kb_series",
                String.valueOf(seriesId),
                null,
                Map.of("action", action, "succeeded", succeeded, "failed", failed));
        return new BatchDocumentResult(succeeded, failed, failedIds, messages);
    }

    public DownloadFile download(long seriesId, long documentId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        Document document = requireDocument(seriesId, documentId);
        if (!storageService.exists(document.storageKey())) {
            throw new PayrollException("源文件不存在");
        }
        return new DownloadFile(
                storageService.resolve(document.storageKey()),
                document.name(),
                document.mimeType());
    }

    private void deleteInternal(long seriesId, Document document) {
        documentStore.softDelete(document.id());
        chunkStore.deleteByDocument(document.id());
        tagStore.deleteDocumentTags(document.id());
        elasticsearchService.deleteDocument(document.id());
        storageService.deleteQuietly(document.storageKey());
    }

    private Document requireDocument(long seriesId, long documentId) {
        Document document =
                documentStore
                        .findById(documentId, seriesId)
                        .orElseThrow(() -> new PayrollException("文档不存在"));
        if (document.deletedAt() != null) {
            throw new PayrollException("文档已删除");
        }
        return document;
    }

    private Directory resolveDirectory(long seriesId, Long directoryId) {
        if (directoryId == null) {
            return directoryStore
                    .findRoot(seriesId)
                    .orElseThrow(() -> new PayrollException("知识库目录结构缺失"));
        }
        return directoryStore
                .findById(directoryId, seriesId)
                .orElseThrow(() -> new PayrollException("目录不存在"));
    }

    private Map<Long, String> directoryPaths(long seriesId) {
        Map<Long, Directory> directories = new HashMap<>();
        for (Directory directory : directoryStore.findBySeries(seriesId)) {
            directories.put(directory.id(), directory);
        }
        Map<Long, String> paths = new LinkedHashMap<>();
        for (Directory directory : directories.values()) {
            List<String> names = new ArrayList<>();
            Directory cursor = directory;
            int guard = 0;
            while (cursor != null && guard++ < 16) {
                names.add(0, cursor.name());
                cursor = cursor.parentId() == null ? null : directories.get(cursor.parentId());
            }
            paths.put(directory.id(), String.join(" / ", names));
        }
        return paths;
    }

    private Map<Long, String> uploaderNames(List<Document> documents) {
        Map<Long, String> names = new HashMap<>();
        for (Document document : documents) {
            if (!names.containsKey(document.uploadedBy())) {
                names.put(
                        document.uploadedBy(),
                        document.uploadedBy() == 0
                                ? "系统管理员"
                                : adminRepository
                                        .findUser(document.uploadedBy())
                                        .map(AppUser::displayName)
                                        .orElse("用户#" + document.uploadedBy()));
            }
        }
        return names;
    }

    private DocumentView view(long seriesId, Document document) {
        Map<Long, List<Tag>> tags =
                tagStore.findByDocuments(List.of(document.id()));
        Map<Long, String> paths = directoryPaths(seriesId);
        return view(
                document,
                paths,
                tags,
                uploaderNames(List.of(document)));
    }

    private DocumentView view(
            Document document,
            Map<Long, String> directoryPaths,
            Map<Long, List<Tag>> tags,
            Map<Long, String> uploaders) {
        List<TagView> tagViews =
                tags.getOrDefault(document.id(), List.of()).stream()
                        .map(tag -> new TagView(tag.id(), tag.name(), tag.color(), 0))
                        .toList();
        return new DocumentView(
                document.id(),
                document.seriesId(),
                document.directoryId(),
                directoryPaths.get(document.directoryId()),
                document.name(),
                document.ext(),
                document.mimeType(),
                document.sizeBytes(),
                document.pageCount(),
                document.charCount(),
                document.status(),
                document.errorCode(),
                document.errorMessage(),
                document.chunkCount(),
                document.configVersionId(),
                document.uploadedBy(),
                uploaders.getOrDefault(document.uploadedBy(), "用户#" + document.uploadedBy()),
                tagViews,
                document.createdAt(),
                document.updatedAt());
    }

    private static String toConfigType(String ext) {
        return switch (ext) {
            case "pdf" -> "PDF";
            case "docx" -> "DOCX";
            case "xlsx", "xls" -> "XLSX";
            case "md", "markdown" -> "MD";
            default -> ext.toUpperCase(Locale.ROOT);
        };
    }

    private static String mimeType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "md", "markdown" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }
}
