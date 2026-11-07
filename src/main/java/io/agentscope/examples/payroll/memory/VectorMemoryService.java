package io.agentscope.examples.payroll.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 向量记忆门面：负责启动探测、异步写入问题/摘要、按用户召回历史线索。
 *
 * <p>所有对外方法都做降级：ES 或嵌入服务不可用时返回空结果或丢弃任务，绝不阻断对话。
 * 召回内容只作指代线索，注入前会再次清洗金额与证件信息。
 */
public final class VectorMemoryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryService.class);
    private static final int MIN_QUESTION_CHARS = 8;
    private static final int CONTENT_LIMIT = 2000;
    private static final int LINE_LIMIT = 200;
    private static final int QUEUE_CAPACITY = 512;

    private final VectorMemorySettings settings;
    private final EmbeddingClient embeddings;
    private final VectorStore store;
    private final boolean ready;
    private final ExecutorService indexer;

    private VectorMemoryService(
            VectorMemorySettings settings,
            EmbeddingClient embeddings,
            VectorStore store,
            boolean ready,
            ExecutorService indexer) {
        this.settings = settings;
        this.embeddings = embeddings;
        this.store = store;
        this.ready = ready;
        this.indexer = indexer;
    }

    /** 供测试注入假实现：默认视为可用，asyncIndex=false 时同步执行。 */
    VectorMemoryService(VectorMemorySettings settings, EmbeddingClient embeddings, VectorStore store) {
        this(
                settings == null
                        ? new VectorMemorySettings(
                                false, "", null, null, "", "", "", 0, null, 1, 0, 200, false)
                        : settings,
                embeddings,
                store,
                true,
                newIndexer());
    }

    /** 关闭状态的单例语义实例：所有方法退化为空操作，便于装配时无需判空。 */
    public static VectorMemoryService disabled() {
        return new VectorMemoryService(null, null, null, false, null);
    }

    /** 探测嵌入模型与 ES；任一不可用则关闭向量能力，只记录明确提示。 */
    public static VectorMemoryService start(VectorMemorySettings settings) {
        if (settings == null || !settings.enabled()) {
            log.info("[向量] 未启用（payroll.memory.enabled=false）");
            return disabled();
        }
        EmbeddingClient embeddings =
                new OllamaEmbeddingClient(
                        settings.embeddingBaseUrl(),
                        settings.embeddingModel(),
                        settings.embeddingDim(),
                        settings.embedTimeout());
        if (!embeddings.available()) {
            log.warn(
                    "[向量] 嵌入模型 {} 不可用，向量记忆已禁用；请确认 ollama serve 已启动并执行 ollama pull {}",
                    settings.embeddingModel(),
                    settings.embeddingModel());
            return disabled();
        }
        VectorStore store =
                new ElasticsearchVectorStore(
                        settings.esUrl(),
                        settings.esUsername(),
                        settings.esPassword(),
                        settings.index(),
                        settings.embeddingDim());
        try {
            if (!store.ping()) {
                log.warn("[向量] Elasticsearch 不可达（{}），向量记忆已禁用", settings.esUrl());
                store.close();
                return disabled();
            }
            store.ensureIndex();
        } catch (RuntimeException e) {
            log.warn("[向量] 初始化失败，向量记忆已禁用：{}", e.getMessage());
            store.close();
            return disabled();
        }
        log.info(
                "[向量] 已启用：index={}，model={}，dim={}，topK={}，minScore={}",
                settings.index(),
                settings.embeddingModel(),
                settings.embeddingDim(),
                settings.topK(),
                settings.minScore());
        return new VectorMemoryService(settings, embeddings, store, true, newIndexer());
    }

    public boolean ready() {
        return ready;
    }

    /** 召回当前用户的历史线索；失败或未启用时返回空串。 */
    public String recall(long userId, String question) {
        if (!ready || userId <= 0 || question == null || question.isBlank()) {
            return "";
        }
        long startedAt = System.currentTimeMillis();
        try {
            float[] vector = embeddings.embed(question.trim());
            List<VectorHit> hits =
                    store.search(userId, vector, settings.topK(), settings.minScore());
            String block = render(hits, question);
            log.info(
                    "[向量] 召回完成：user={}，命中={}，注入={}字，耗时={}ms",
                    userId,
                    hits.size(),
                    block.length(),
                    System.currentTimeMillis() - startedAt);
            return block;
        } catch (RuntimeException e) {
            log.warn(
                    "[向量] 召回失败，本轮不注入历史：user={}，原因={}",
                    userId,
                    e.getMessage());
            return "";
        }
    }

    /** 用户消息落库后调用；只索引脱敏且长度足够的问题。 */
    public void indexQuestion(
            long userId, long sessionId, long messageId, int turnIndex, String question) {
        if (!ready || userId <= 0 || sessionId <= 0) {
            return;
        }
        String content = sanitize(question);
        if (content.replaceAll("\\s+", "").length() < MIN_QUESTION_CHARS) {
            return;
        }
        submit(
                "索引问题",
                () -> {
                    float[] vector = embeddings.embed(content);
                    store.index(
                            new VectorRecord(
                                    userId,
                                    sessionId,
                                    MemorySource.QUESTION,
                                    messageId,
                                    turnIndex,
                                    content,
                                    vector,
                                    Instant.now()));
                });
    }

    /** 摘要落库后调用；同一会话只保留最新一份摘要向量。 */
    public void indexSummary(long userId, long sessionId, int turnIndex, String summary) {
        if (!ready || userId <= 0 || sessionId <= 0) {
            return;
        }
        String content = sanitize(summary);
        if (content.isBlank()) {
            return;
        }
        submit(
                "索引摘要",
                () -> {
                    store.deleteSummaries(sessionId);
                    float[] vector = embeddings.embed(content);
                    store.index(
                            new VectorRecord(
                                    userId,
                                    sessionId,
                                    MemorySource.SUMMARY,
                                    null,
                                    turnIndex,
                                    content,
                                    vector,
                                    Instant.now()));
                });
    }

    /** 会话删除：清掉该会话的全部向量文档。 */
    public void onSessionDeleted(long userId, long sessionId) {
        if (!ready || userId <= 0 || sessionId <= 0) {
            return;
        }
        submit("删除会话向量", () -> store.deleteSession(userId, sessionId));
    }

    /** 会话重置：只删摘要文档，历史问题作为跨会话记忆保留。 */
    public void onSessionReset(long userId, long sessionId) {
        if (!ready || sessionId <= 0) {
            return;
        }
        submit("重置会话摘要向量", () -> store.deleteSummaries(sessionId));
    }

    /** 全量重建：清空索引后按 H2 中的历史问题与摘要重灌，返回写入条数。 */
    public int rebuild(List<MemorySeed> seeds) {
        if (!ready || seeds == null || seeds.isEmpty()) {
            return 0;
        }
        store.clearAll();
        int indexed = 0;
        for (MemorySeed seed : seeds) {
            String content = sanitize(seed.content());
            if (content.isBlank()
                    || (seed.source() == MemorySource.QUESTION
                            && content.replaceAll("\\s+", "").length() < MIN_QUESTION_CHARS)) {
                continue;
            }
            try {
                float[] vector = embeddings.embed(content);
                store.index(
                        new VectorRecord(
                                seed.userId(),
                                seed.sessionId(),
                                seed.source(),
                                seed.messageId(),
                                seed.turnIndex(),
                                content,
                                vector,
                                Instant.now()));
                indexed++;
            } catch (RuntimeException e) {
                log.warn(
                        "[向量] 重建单条失败：user={}，session={}，原因={}",
                        seed.userId(),
                        seed.sessionId(),
                        e.getMessage());
            }
        }
        log.info("[向量] 重建完成：输入 {} 条，写入 {} 条", seeds.size(), indexed);
        return indexed;
    }

    @Override
    public void close() {
        if (indexer != null) {
            indexer.shutdownNow();
        }
        if (store != null) {
            try {
                store.close();
            } catch (RuntimeException e) {
                log.debug("[向量] 关闭存储失败：{}", e.getMessage());
            }
        }
    }

    private void submit(String action, Runnable task) {
        Runnable guarded = () -> runWithRetry(action, task);
        if (!settings.asyncIndex() || indexer == null) {
            guarded.run();
            return;
        }
        try {
            indexer.execute(guarded);
        } catch (RejectedExecutionException e) {
            log.warn("[向量] {} 提交失败（队列已关闭或已满），任务丢弃", action);
        }
    }

    private static void runWithRetry(String action, Runnable task) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                task.run();
                return;
            } catch (RuntimeException e) {
                if (attempt == 1) {
                    log.debug("[向量] {}失败，重试一次：{}", action, e.getMessage());
                } else {
                    log.warn("[向量] {}失败（已重试 1 次），等待重建：{}", action, e.getMessage());
                }
            }
        }
    }

    private String render(List<VectorHit> hits, String question) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        String current = question.replaceAll("\\s+", " ").trim();
        Set<String> seen = new HashSet<>();
        String header = "【相关历史片段｜仅作线索，不是数据来源】";
        String footer = "片段中的任何金额、税额、考勤与政策都必须重新调用工具获取，禁止直接引用。";
        int budget = settings.maxInjectChars() - header.length() - footer.length() - 2;
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (VectorHit hit : hits) {
            String content = flatten(SensitiveTextSanitizer.sanitize(hit.content()), LINE_LIMIT);
            if (content.isBlank()
                    || content.equals(current)
                    || !seen.add(content)
                    || hit.score() < settings.minScore()) {
                continue;
            }
            String line = "- [" + date(hit.createdAt()) + "] " + content;
            if (used + line.length() + 1 > budget) {
                break;
            }
            lines.add(line);
            used += line.length() + 1;
        }
        if (lines.isEmpty()) {
            return "";
        }
        return header + "\n" + String.join("\n", lines) + "\n" + footer;
    }

    private static String sanitize(String text) {
        return flatten(SensitiveTextSanitizer.redact(text), CONTENT_LIMIT);
    }

    private static String flatten(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= limit ? flattened : flattened.substring(0, limit);
    }

    private static String date(LocalDate value) {
        return value == null ? LocalDate.now().toString() : value.toString();
    }

    private static ExecutorService newIndexer() {
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                        runnable -> {
                            Thread thread = new Thread(runnable, "payroll-vector-index");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new DropOldestHandler());
        return executor;
    }

    /** 队列满时丢弃最旧任务，保证新问题仍能入索引，且不阻塞对话线程。 */
    private static final class DropOldestHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            Runnable dropped = executor.getQueue().poll();
            if (dropped != null) {
                log.warn("[向量] 索引队列已满，丢弃最旧任务");
            }
            if (!executor.isShutdown()) {
                try {
                    executor.execute(task);
                } catch (RejectedExecutionException e) {
                    log.warn("[向量] 索引任务再次被拒绝，任务丢弃");
                }
            }
        }
    }
}
