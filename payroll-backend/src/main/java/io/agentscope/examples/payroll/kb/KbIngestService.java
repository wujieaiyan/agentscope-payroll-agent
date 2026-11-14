package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbChunkingService.Draft;
import io.agentscope.examples.payroll.kb.KbConfigModels.ConfigPayload;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbModels.IngestTask;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 入库流水线：解析 → 分块 → 嵌入 → 写 H2 与 ES，支持取消与失败落库。 */
@Service
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);
    private static final int EMBED_BATCH = 16;

    private final KbTaskStore taskStore;
    private final KbDocumentStore documentStore;
    private final KbChunkStore chunkStore;
    private final KbSeriesService seriesService;
    private final KbConfigService configService;
    private final KbParserService parserService;
    private final KbChunkingService chunkingService;
    private final KbEmbeddingService embeddingService;
    private final KbElasticsearchService elasticsearchService;
    private final KbStorageService storageService;

    public KbIngestService(
            KbTaskStore taskStore,
            KbDocumentStore documentStore,
            KbChunkStore chunkStore,
            KbSeriesService seriesService,
            KbConfigService configService,
            KbParserService parserService,
            KbChunkingService chunkingService,
            KbEmbeddingService embeddingService,
            KbElasticsearchService elasticsearchService,
            KbStorageService storageService) {
        this.taskStore = taskStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.seriesService = seriesService;
        this.configService = configService;
        this.parserService = parserService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.elasticsearchService = elasticsearchService;
        this.storageService = storageService;
    }

    public void run(long taskId) {
        IngestTask task = taskStore.findById(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.QUEUED) {
            return;
        }
        if (taskStore.markRunning(taskId, "PREPARE", 2) == 0) {
            return;
        }
        long started = System.currentTimeMillis();
        Document document =
                task.documentId() == null
                        ? null
                        : documentStore.findById(task.documentId()).orElse(null);
        if (document == null || document.deletedAt() != null) {
            fail(taskId, null, "DOCUMENT_MISSING", "文档不存在或已删除", started);
            return;
        }
        try {
            log(taskId, "PREPARE", "开始入库：" + document.name(), null);
            ensureNotCanceled(taskId);
            documentStore.updateStatus(document.id(), DocStatus.PARSING, null, null);
            Path file = storageService.resolve(document.storageKey());
            if (!storageService.exists(document.storageKey())) {
                throw new PayrollException("源文件不存在，请重新上传文档");
            }
            ConfigVersion config = configService.requireConfig(document.seriesId(), task.configVersionId());
            if (config == null) {
                throw new PayrollException("知识库尚未配置解析规则");
            }
            ConfigPayload payload = configService.payload(config);

            long stage = System.currentTimeMillis();
            KbParsedDocument parsed = parserService.parse(file, document.ext());
            if (parsed.text() == null || parsed.text().strip().length() < 10) {
                throw new PayrollException("解析文本过短或为空，请确认文档包含可提取文字");
            }
            log(
                    taskId,
                    "PARSE",
                    "解析完成：页数="
                            + parsed.pageCount()
                            + "，字符数="
                            + parsed.text().length(),
                    (int) (System.currentTimeMillis() - stage));
            taskStore.updateProgress(taskId, "PARSE", 30);
            ensureNotCanceled(taskId);

            documentStore.updateStatus(document.id(), DocStatus.CHUNKING, null, null);
            stage = System.currentTimeMillis();
            List<Draft> drafts = chunkingService.chunk(parsed, document.name(), payload.chunk);
            if (drafts.isEmpty()) {
                throw new PayrollException("分块结果为空，请调整分块配置后重试");
            }
            log(
                    taskId,
                    "CHUNK",
                    "分块完成：" + drafts.size() + " 块（策略 " + payload.chunk.strategy + "）",
                    (int) (System.currentTimeMillis() - stage));
            taskStore.updateProgress(taskId, "CHUNK", 45);
            ensureNotCanceled(taskId);

            documentStore.updateStatus(document.id(), DocStatus.INDEXING, null, null);
            chunkStore.deleteByDocument(document.id());
            elasticsearchService.deleteDocument(document.id());

            List<Long> chunkIds = persistDrafts(document, drafts);
            ensureNotCanceled(taskId);
            embedChunks(taskId, chunkIds, drafts);

            List<Chunk> persisted = chunkStore.findByDocument(document.id());
            List<Chunk> indexed = new ArrayList<>();
            for (Chunk chunk : persisted) {
                if (chunk.level() != ChunkLevel.PARENT) {
                    indexed.add(chunk);
                }
            }
            stage = System.currentTimeMillis();
            try {
                elasticsearchService.indexChunks(
                        document.seriesId(),
                        document.id(),
                        document.name(),
                        document.ext(),
                        persisted);
                log(
                        taskId,
                        "INDEX",
                        "ES 索引完成："
                                + indexed.size()
                                + " 块（"
                                + (elasticsearchService.available() ? "已连接" : "不可用，仅本地检索")
                                + "）",
                        (int) (System.currentTimeMillis() - stage));
            } catch (RuntimeException e) {
                log(taskId, "INDEX", "ES 索引失败，仅保留本地索引：" + e.getMessage(), null);
                log.warn("[知识库] ES 索引失败：{}", e.getMessage());
            }

            documentStore.updateResult(
                    document.id(),
                    parsed.pageCount() > 0 ? parsed.pageCount() : null,
                    (long) parsed.text().length(),
                    indexed.size(),
                    config.id());
            documentStore.updateStatus(document.id(), DocStatus.READY, null, null);
            taskStore.markSucceeded(taskId);
            seriesService.refreshCounters(document.seriesId());
            log(
                    taskId,
                    "DONE",
                    "入库成功：" + indexed.size() + " 个检索块",
                    (int) (System.currentTimeMillis() - started));
        } catch (IngestCanceled canceled) {
            cleanup(document);
            if (document != null) {
                documentStore.updateStatus(document.id(), DocStatus.PENDING, null, "入库任务已取消");
                seriesService.refreshCounters(document.seriesId());
            }
            taskStore.markCanceled(taskId);
            log(taskId, "CANCEL", "任务已取消并清理临时分块", null);
        } catch (Exception e) {
            log.error("[知识库] 入库失败：task={}，document={}", taskId, document.id(), e);
            cleanup(document);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (document != null) {
                documentStore.updateStatus(document.id(), DocStatus.FAILED, "INGEST_FAILED", message);
                seriesService.refreshCounters(document.seriesId());
            }
            taskStore.appendLog(taskId, "FAILED", "ERROR", message, null);
            taskStore.markFailed(taskId, "INGEST_FAILED", message, null);
        }
    }

    private List<Long> persistDrafts(Document document, List<Draft> drafts) {
        List<Long> ids = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            Long parentChunkId =
                    draft.parentIndex() == null ? null : ids.get(draft.parentIndex());
            Chunk chunk =
                    new Chunk(
                            0L,
                            document.seriesId(),
                            document.id(),
                            parentChunkId,
                            draft.level(),
                            draft.seq(),
                            draft.content(),
                            draft.contentHash(),
                            draft.tokenCount(),
                            draft.charStart(),
                            draft.charEnd(),
                            draft.pageNo(),
                            null,
                            draft.titlePath(),
                            draft.metadata(),
                            null,
                            null,
                            "ACTIVE",
                            Instant.now());
            ids.add(chunkStore.insert(chunk));
        }
        return ids;
    }

    private void embedChunks(long taskId, List<Long> ids, List<Draft> drafts) {
        int embedded = 0;
        String source = "HASH";
        for (int start = 0; start < drafts.size(); start += EMBED_BATCH) {
            ensureNotCanceled(taskId);
            int end = Math.min(start + EMBED_BATCH, drafts.size());
            List<String> texts = new ArrayList<>();
            List<Long> targets = new ArrayList<>();
            for (int i = start; i < end; i++) {
                if (drafts.get(i).level() == ChunkLevel.PARENT) {
                    continue;
                }
                texts.add(drafts.get(i).content());
                targets.add(ids.get(i));
            }
            if (texts.isEmpty()) {
                continue;
            }
            KbEmbeddingService.Embedding embedding = embeddingService.embed(texts);
            source = embedding.source();
            for (int i = 0; i < targets.size(); i++) {
                chunkStore.updateVector(
                        targets.get(i), embeddingService.toJson(embedding.vectors().get(i)), source);
            }
            embedded += targets.size();
            taskStore.updateProgress(
                    taskId, "EMBED", 45 + Math.min(45, (int) (45.0 * end / drafts.size())));
        }
        taskStore.appendLog(taskId, "EMBED", "INFO", "嵌入完成：" + embedded + " 块，来源 " + source, null);
    }

    private void cleanup(Document document) {
        if (document == null) {
            return;
        }
        try {
            chunkStore.deleteByDocument(document.id());
        } catch (RuntimeException e) {
            log.warn("[知识库] 清理分块失败：{}", e.getMessage());
        }
        try {
            elasticsearchService.deleteDocument(document.id());
        } catch (RuntimeException e) {
            log.warn("[知识库] 清理索引失败：{}", e.getMessage());
        }
    }

    private void ensureNotCanceled(long taskId) {
        if (taskStore.cancelRequested(taskId)) {
            throw new IngestCanceled();
        }
    }

    private void fail(long taskId, Document document, String code, String message, long started) {
        if (document != null) {
            documentStore.updateStatus(document.id(), DocStatus.FAILED, code, message);
            seriesService.refreshCounters(document.seriesId());
        }
        taskStore.appendLog(taskId, "FAILED", "ERROR", message, null);
        taskStore.markFailed(taskId, code, message, null);
        log(taskId, "FAILED", message, (int) (System.currentTimeMillis() - started));
    }

    private void log(long taskId, String stage, String message, Integer costMs) {
        taskStore.appendLog(taskId, stage, "INFO", message, costMs);
    }

    private static final class IngestCanceled extends RuntimeException {
        private IngestCanceled() {
            super(null, null, false, false);
        }
    }
}
