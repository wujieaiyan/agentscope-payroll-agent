package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbConfigModels.RetrievalConfig;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalHit;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalResult;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbRetrievalSettings.Route;
import io.agentscope.examples.payroll.support.LexicalTokens;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 知识库检索：多路召回 + RRF 融合 + 知识点合并；ES 不可用时自动退化本地实现。 */
@Service
public class KbRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalService.class);

    private static final double ADJACENT_OVERLAP = 0.6;
    private static final int MAX_PARENT_CONTENT = 1200;
    /** 主题词在语料中至少出现这么多次才视为“收录”，避免书单页/命令列表里的一次顺带提及。 */
    private static final int MIN_TOPIC_MENTIONS = 3;
    /** ES 关键词一次预取的样本下限：足够主题门禁统计提及次数，BM25 路由再按召回上限截断。 */
    private static final int MIN_KEYWORD_GATE_SIZE = 50;
    /** 仅用于并行的非嵌入召回阶段；嵌入本身在进入并行前已完成。 */
    private static final ExecutorService RECALL_POOL =
            Executors.newFixedThreadPool(
                    3,
                    r -> {
                        Thread thread = new Thread(r, "kb-recall");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final KbChunkStore chunkStore;
    private final KbDocumentStore documentStore;
    private final KbConfigService configService;
    private final KbPermissionService permissionService;
    private final KbEmbeddingService embeddingService;
    private final KbElasticsearchService elasticsearchService;
    private final KbAnswerService answerService;
    private final KbRetrievalSettings settings;

    public KbRetrievalService(
            KbChunkStore chunkStore,
            KbDocumentStore documentStore,
            KbConfigService configService,
            KbPermissionService permissionService,
            KbEmbeddingService embeddingService,
            KbElasticsearchService elasticsearchService,
            KbAnswerService answerService,
            KbRetrievalSettings settings) {
        this.chunkStore = chunkStore;
        this.documentStore = documentStore;
        this.configService = configService;
        this.permissionService = permissionService;
        this.embeddingService = embeddingService;
        this.elasticsearchService = elasticsearchService;
        this.answerService = answerService;
        this.settings = settings;
    }

    private record ScoredHit(
            Chunk chunk,
            double score,
            double vectorScore,
            double keywordScore,
            String content,
            List<String> routes,
            Map<String, Integer> routeRanks,
            int mergedChunks) {

        ScoredHit(Chunk chunk, double score, double vectorScore, double keywordScore) {
            this(chunk, score, vectorScore, keywordScore, chunk.content(), List.of(), Map.of(), 1);
        }

        ScoredHit {
            content = content == null ? chunk.content() : content;
            routes = routes == null ? List.of() : List.copyOf(routes);
            routeRanks = routeRanks == null ? Map.of() : Map.copyOf(routeRanks);
            mergedChunks = Math.max(1, mergedChunks);
        }
    }

    private record RouteHit(Chunk chunk, double score) {
    }

    private record RouteHits(List<RouteHit> hits, boolean elasticsearch, boolean degraded) {
        static RouteHits empty() {
            return new RouteHits(List.of(), false, false);
        }

        static RouteHits local(List<RouteHit> hits) {
            return new RouteHits(hits, false, false);
        }
    }

    /** ES 关键词命中（原始相关度）与是否降级，供主题门禁与 BM25-ES 路由共用一次请求。 */
    private record KeywordHits(List<KbElasticsearchService.SearchHit> hits, boolean degraded) {
        static KeywordHits none() {
            return new KeywordHits(List.of(), false);
        }
    }

    private record LegacyOutcome(List<ScoredHit> hits, String engine) {
    }

    private record FusionOutcome(
            List<ScoredHit> hits, String engine, Map<String, Object> diagnostics) {
    }

    private record DedupeResult(List<ScoredHit> hits, int removed) {
    }

    public RetrievalResult search(long seriesId, RetrievalTestRequest request, AuthUser user) {
        return search(seriesId, request, user, null);
    }

    /** 多库检索时由调用方预计算查询向量，避免每个库重复调用嵌入服务。 */
    public RetrievalResult search(
            long seriesId,
            RetrievalTestRequest request,
            AuthUser user,
            KbEmbeddingService.Embedding precomputed) {
        permissionService.requireRead(seriesId, user);
        long started = System.currentTimeMillis();
        String query = request == null || request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new PayrollException("请输入检索问题");
        }
        if (query.length() > 200) {
            throw new PayrollException("检索问题不能超过 200 个字符");
        }
        ConfigVersion version = configService.requireActiveConfig(seriesId);
        RetrievalConfig config =
                version == null
                        ? KbConfigModels.defaultRetrieval()
                        : configService.payload(version).retrieval;
        String strategy = version == null ? "HYBRID" : version.indexStrategy().name();
        boolean hybrid = !"VECTOR".equals(strategy) && config.hybridEnabled;
        double threshold =
                request.threshold() == null ? config.similarityThreshold : request.threshold();
        threshold = Math.max(0, Math.min(1, threshold));
        int recallLimit = Math.max(1, Math.min(100, config.recallTopK));
        int defaultLimit = Math.max(1, Math.min(config.rerankTopN, recallLimit));
        int resultLimit =
                request.topK() == null
                        ? defaultLimit
                        : Math.max(1, Math.min(20, request.topK()));

        Map<Long, Document> readyDocuments = readyDocuments(seriesId);
        if (readyDocuments.isEmpty()) {
            return empty(query, strategy, started, false, hybrid);
        }

        KbEmbeddingService.Embedding embedding =
                precomputed == null ? embeddingService.embedQuery(query) : precomputed;
        float[] queryVector =
                embedding.vectors().isEmpty() ? null : embedding.vectors().get(0);
        String engine = "LOCAL";
        List<ScoredHit> hits;
        Map<String, Object> routeDiagnostics = new LinkedHashMap<>();
        // 同一轮检索内复用分词结果：各召回、重排与相邻合并都按内容字符串命中同一份缓存。
        Map<String, List<String>> tokenCache = new ConcurrentHashMap<>();

        if (settings.fusionEnabled()) {
            FusionOutcome outcome =
                    fuse(
                            seriesId,
                            query,
                            queryVector,
                            readyDocuments,
                            config,
                            hybrid,
                            threshold,
                            recallLimit,
                            tokenCache);
            hits = outcome.hits();
            engine = outcome.engine();
            routeDiagnostics.putAll(outcome.diagnostics());
            if (config.rerankEnabled && !hits.isEmpty()) {
                hits = rerank(query, hits, tokenCache);
            }
        } else {
            LegacyOutcome outcome =
                    legacy(
                            seriesId,
                            query,
                            queryVector,
                            readyDocuments,
                            config,
                            hybrid,
                            threshold,
                            recallLimit,
                            tokenCache);
            hits = outcome.hits();
            engine = outcome.engine();
            routeDiagnostics.put("fusion", false);
        }
        List<ScoredHit> limited =
                hits.size() > resultLimit ? hits.subList(0, resultLimit) : hits;
        List<RetrievalHit> citations = citations(limited, readyDocuments);
        long retrievalTook = System.currentTimeMillis() - started;

        KbAnswerService.Answer answer = null;
        boolean withAnswer = request.withAnswer() == null || request.withAnswer();
        if (withAnswer && !citations.isEmpty()) {
            answer = answerService.answer(query, citations);
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("engine", engine);
        diagnostics.put("hybrid", hybrid);
        diagnostics.put("rerank", config.rerankEnabled);
        diagnostics.put("threshold", round(threshold));
        diagnostics.put("recallTopK", recallLimit);
        diagnostics.put("resultLimit", resultLimit);
        diagnostics.put("embeddingSource", embedding.source());
        diagnostics.put("esAvailable", elasticsearchService.available());
        diagnostics.putAll(routeDiagnostics);
        if (answer != null) {
            diagnostics.put("answerModel", answer.model());
            diagnostics.put("answerGenerated", answer.generated());
            diagnostics.put("answerTookMs", answer.tookMs());
            diagnostics.put("answerError", answer.error());
        }
        log.info(
                "[知识库] 检索完成：series={}，engine={}，query=[{}]，命中={}，阈值={}，耗时={}ms",
                seriesId,
                engine,
                query,
                citations.size(),
                round(threshold),
                System.currentTimeMillis() - started);
        return new RetrievalResult(
                query,
                strategy,
                System.currentTimeMillis() - started,
                citations,
                answer == null ? null : answer.text(),
                embedding.ollama() && queryVector != null,
                diagnostics);
    }

    /**
     * 检索候选只取元数据并按上限截断；ES BM25 可用时只取键列（无 content/vector），正文后续按命中回表。
     */
    private List<Chunk> loadCandidates(
            long seriesId, Map<Long, Document> readyDocuments, boolean withContent) {
        List<Chunk> loaded =
                withContent
                        ? chunkStore.findActiveMetaBySeries(seriesId, settings.maxCandidates())
                        : chunkStore.findActiveKeysBySeries(seriesId, settings.maxCandidates());
        List<Chunk> candidates = new ArrayList<>();
        for (Chunk chunk : loaded) {
            if (valid(chunk, readyDocuments)) {
                candidates.add(chunk);
            }
        }
        return candidates;
    }

    /** 批量回取向量的浮点表示；缺失或解析失败的分块按无向量处理。 */
    private Map<Long, float[]> loadVectors(List<Chunk> candidates) {
        List<Long> ids = candidates.stream().map(Chunk::id).toList();
        Map<Long, String> raw = chunkStore.findVectorsByIds(ids);
        Map<Long, float[]> vectors = new HashMap<>(raw.size());
        raw.forEach(
                (id, json) -> {
                    float[] vector = embeddingService.fromJson(json);
                    if (vector != null) {
                        vectors.put(id, vector);
                    }
                });
        return vectors;
    }

    /** 融合关闭时的旧路径，保留用于灰度回滚。 */
    private LegacyOutcome legacy(
            long seriesId,
            String query,
            float[] queryVector,
            Map<Long, Document> readyDocuments,
            RetrievalConfig config,
            boolean hybrid,
            double threshold,
            int recallLimit,
            Map<String, List<String>> tokenCache) {
        String engine = "LOCAL";
        List<Chunk> candidates = loadCandidates(seriesId, readyDocuments, true);
        List<String> topics = LexicalTokens.asciiTerms(query);
        if (!topics.isEmpty()) {
            if (!mentionsAnyTopic(topics, candidates, readyDocuments)) {
                log.info("[知识库] 检索主题不在库内：series={}，terms={}", seriesId, topics);
                return new LegacyOutcome(List.of(), engine);
            }
        }
        List<ScoredHit> hits = new ArrayList<>();
        if (elasticsearchService.available()) {
            List<KbElasticsearchService.SearchHit> esHits =
                    elasticsearchService.search(
                            seriesId, queryVector, query, recallLimit, hybrid, config.vectorWeight);
            if (!esHits.isEmpty()) {
                hits = fromElasticsearch(esHits, readyDocuments);
                if (!hits.isEmpty()) {
                    engine = "ELASTICSEARCH";
                }
            }
        }
        if (hits.isEmpty()) {
            hits = searchLocally(query, queryVector, candidates, hybrid, config, tokenCache);
            engine = "LOCAL";
        }
        hits = hits.stream().filter(hit -> hit.score() >= threshold).toList();
        if (config.rerankEnabled && !hits.isEmpty()) {
            hits = rerank(query, hits, tokenCache);
        }
        return new LegacyOutcome(hits, engine);
    }

    /** 每路独立召回后按 RRF 融合，再做知识点去重、相邻合并与单文档限流。 */
    private FusionOutcome fuse(
            long seriesId,
            String query,
            float[] queryVector,
            Map<Long, Document> readyDocuments,
            RetrievalConfig config,
            boolean hybrid,
            double threshold,
            int recallLimit,
            Map<String, List<String>> tokenCache) {
        List<Route> routes = new ArrayList<>(settings.recallRoutes());
        if (!hybrid) {
            routes.removeIf(route -> route != Route.VECTOR);
        }
        if (routes.isEmpty()) {
            return new FusionOutcome(List.of(), "LOCAL", diagnostics(true, 0, 0, Map.of()));
        }
        // ES BM25 可用时候选只取键列（无 content/vector），正文在召回后再按命中回表。
        boolean esBm25 = settings.bm25EngineEs() && elasticsearchService.available();
        List<Chunk> candidates = loadCandidates(seriesId, readyDocuments, !esBm25);
        if (candidates.isEmpty()) {
            return new FusionOutcome(List.of(), "LOCAL", diagnostics(true, 0, 0, Map.of()));
        }
        Map<Long, Chunk> byId = new HashMap<>();
        for (Chunk chunk : candidates) {
            byId.put(chunk.id(), chunk);
        }

        List<String> topics = LexicalTokens.asciiTerms(query);
        // ES 关键词命中供主题门禁与 BM25-ES 路由共用，一次请求避免重复打分。
        KeywordHits keywords = KeywordHits.none();
        if (esBm25 && (!topics.isEmpty() || routes.contains(Route.BM25))) {
            keywords = fetchKeywordHits(seriesId, query, Math.max(recallLimit, MIN_KEYWORD_GATE_SIZE));
        }
        Map<Long, String> keywordContents = Map.of();
        if (!keywords.hits().isEmpty()) {
            List<Long> keywordIds =
                    keywords.hits().stream()
                            .map(KbElasticsearchService.SearchHit::chunkId)
                            .distinct()
                            .toList();
            keywordContents = chunkStore.findContentByIds(keywordIds);
        }

        // 问题点名了具体主题（如 Kafka）但库内语料完全没有该词时，任何命中都只是字面噪声。
        if (!topics.isEmpty()) {
            boolean covered;
            if (esBm25) {
                // 键列候选无正文：标题/文档名直接判定；正文侧只看 ES 关键词命中的回表内容。
                covered =
                        mentionsTopicInTitles(topics, candidates, readyDocuments)
                                || keywords.degraded()
                                || mentionsTopicInContents(topics, keywordContents);
            } else {
                covered = mentionsAnyTopic(topics, candidates, readyDocuments);
            }
            if (!covered) {
                Map<String, Object> emptyDiagnostics = diagnostics(true, 0, 0, Map.of());
                emptyDiagnostics.put("reason", "QUERY_TERM_NOT_IN_CORPUS");
                emptyDiagnostics.put("topicTerms", topics);
                log.info("[知识库] 检索主题不在库内：series={}，terms={}", seriesId, topics);
                return new FusionOutcome(List.of(), "LOCAL", emptyDiagnostics);
            }
        }

        Map<String, Object> routeStats = new LinkedHashMap<>();
        Map<Route, Long> routeTook = new ConcurrentHashMap<>();
        // 查询向量已在进入融合前算好，这里并行的是纯召回/评分阶段，不会并发压嵌入服务。
        boolean concurrent = routes.size() > 1;
        KeywordHits keywordHits = keywords;
        CompletableFuture<RouteHits> vectorFuture =
                routes.contains(Route.VECTOR)
                        ? submitRoute(
                                concurrent,
                                () ->
                                        timed(
                                                Route.VECTOR,
                                                routeTook,
                                                () ->
                                                        vectorRoute(
                                                                seriesId,
                                                                queryVector,
                                                                candidates,
                                                                byId,
                                                                readyDocuments,
                                                                threshold,
                                                                recallLimit)))
                        : CompletableFuture.completedFuture(RouteHits.empty());
        CompletableFuture<RouteHits> bm25Future =
                routes.contains(Route.BM25)
                        ? submitRoute(
                                concurrent,
                                () ->
                                        timed(
                                                Route.BM25,
                                                routeTook,
                                                () ->
                                                        bm25Route(
                                                                query,
                                                                candidates,
                                                                byId,
                                                                keywordHits,
                                                                esBm25,
                                                                recallLimit,
                                                                tokenCache)))
                        : CompletableFuture.completedFuture(RouteHits.empty());
        CompletableFuture<RouteHits> titleFuture =
                routes.contains(Route.TITLE_PATH)
                        ? submitRoute(
                                concurrent,
                                () ->
                                        timed(
                                                Route.TITLE_PATH,
                                                routeTook,
                                                () ->
                                                        titleRoute(
                                                                query,
                                                                candidates,
                                                                readyDocuments,
                                                                recallLimit,
                                                                tokenCache)))
                        : CompletableFuture.completedFuture(RouteHits.empty());
        RouteHits vector = vectorFuture.join();
        RouteHits bm25 = bm25Future.join();
        RouteHits title = titleFuture.join();
        if (routes.contains(Route.VECTOR)) {
            routeStats.put("VECTOR", routeStat(vector.hits().size(), routeTook.get(Route.VECTOR)));
        }
        if (routes.contains(Route.BM25)) {
            routeStats.put("BM25", routeStat(bm25.hits().size(), routeTook.get(Route.BM25)));
        }
        if (routes.contains(Route.TITLE_PATH)) {
            routeStats.put(
                    "TITLE_PATH", routeStat(title.hits().size(), routeTook.get(Route.TITLE_PATH)));
        }

        Map<Long, AccumulatedHit> accumulated = new LinkedHashMap<>();
        addRoute(accumulated, Route.VECTOR, vector.hits(), settings.weight(Route.VECTOR));
        addRoute(accumulated, Route.BM25, bm25.hits(), settings.weight(Route.BM25));
        addRoute(accumulated, Route.TITLE_PATH, title.hits(), settings.weight(Route.TITLE_PATH));

        // 正文只在融合命中集合上回表，禁止全候选装载内容。
        Map<Long, String> contents = collectContents(accumulated, keywordContents);

        double maxScore = 0;
        for (AccumulatedHit hit : accumulated.values()) {
            maxScore = Math.max(maxScore, hit.fused);
        }
        List<ScoredHit> fused = new ArrayList<>(accumulated.size());
        for (AccumulatedHit hit : accumulated.values()) {
            fused.add(hit.toScoredHit(maxScore, contents));
        }
        fused.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        int fusedCount = fused.size();

        DedupeResult deduped = dedupeByFingerprint(fused);
        DedupeResult merged = adjacentMerge(deduped.hits(), tokenCache);
        List<ScoredHit> capped = capPerDocument(merged.hits());
        int removed = deduped.removed() + merged.removed();
        String engine = vector.elasticsearch() ? "ELASTICSEARCH" : "LOCAL";
        Map<String, Object> diagnostics = diagnostics(true, fusedCount, removed, routeStats);
        diagnostics.put("bm25_engine", esBm25 ? "es" : "local");
        if (esBm25) {
            diagnostics.put("keysOnly", true);
        }
        if (vector.degraded()) {
            diagnostics.put("vectorFallback", "LOCAL");
            diagnostics.put("esDegraded", true);
        }
        if (bm25.degraded()) {
            diagnostics.put("bm25Degraded", true);
            diagnostics.put("esDegraded", true);
        }
        return new FusionOutcome(capped, engine, diagnostics);
    }

    private KeywordHits fetchKeywordHits(long seriesId, String query, int size) {
        try {
            return new KeywordHits(elasticsearchService.keywordSearch(seriesId, query, size), false);
        } catch (RuntimeException e) {
            log.warn("[知识库] ES 关键词召回失败，BM25 路由本次降级：{}", e.getMessage());
            return new KeywordHits(List.of(), true);
        }
    }

    /** 为融合命中的分块批量回表正文；标题/键列候选本身不带正文。 */
    private Map<Long, String> collectContents(
            Map<Long, AccumulatedHit> accumulated, Map<Long, String> preloaded) {
        Map<Long, String> contents = new HashMap<>(preloaded);
        List<Long> missing = new ArrayList<>();
        for (AccumulatedHit hit : accumulated.values()) {
            Chunk chunk = hit.chunk;
            if (chunk.content() != null) {
                contents.put(chunk.id(), chunk.content());
            } else if (!contents.containsKey(chunk.id())) {
                missing.add(chunk.id());
            }
        }
        if (!missing.isEmpty()) {
            contents.putAll(chunkStore.findContentByIds(missing));
        }
        return contents;
    }

    private static CompletableFuture<RouteHits> submitRoute(
            boolean concurrent, Supplier<RouteHits> task) {
        if (!concurrent) {
            return CompletableFuture.completedFuture(task.get());
        }
        return CompletableFuture.supplyAsync(task, RECALL_POOL);
    }

    private static RouteHits timed(
            Route route, Map<Route, Long> took, Supplier<RouteHits> task) {
        long started = System.currentTimeMillis();
        try {
            return task.get();
        } finally {
            took.put(route, System.currentTimeMillis() - started);
        }
    }

    /** 主题词必须出现在文档名/标题路径，或在语料中反复出现（≥3 次）才算库内覆盖；单次顺带提及多为无关页面噪声。 */
    private static boolean mentionsAnyTopic(
            List<String> topics, List<Chunk> candidates, Map<Long, Document> documents) {
        Map<String, Integer> mentions = new HashMap<>();
        for (Chunk chunk : candidates) {
            Document document = documents.get(chunk.documentId());
            String title =
                    ((chunk.titlePath() == null ? "" : chunk.titlePath())
                                    + "\n"
                                    + (document == null || document.name() == null
                                            ? ""
                                            : document.name()))
                            .toLowerCase(Locale.ROOT);
            String content = (chunk.content() == null ? "" : chunk.content()).toLowerCase(Locale.ROOT);
            for (String topic : topics) {
                if (title.contains(topic)) {
                    return true;
                }
                mentions.merge(topic, countOccurrences(content, topic), Integer::sum);
            }
        }
        return mentions.values().stream().anyMatch(count -> count >= MIN_TOPIC_MENTIONS);
    }

    private static int countOccurrences(String text, String term) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(term, from)) >= 0) {
            count++;
            from += term.length();
        }
        return count;
    }

    /** 键列候选只看标题路径/文档名是否点名主题，避免为门禁加载全部正文。 */
    private static boolean mentionsTopicInTitles(
            List<String> topics, List<Chunk> candidates, Map<Long, Document> documents) {
        for (Chunk chunk : candidates) {
            Document document = documents.get(chunk.documentId());
            String title =
                    ((chunk.titlePath() == null ? "" : chunk.titlePath())
                                    + "\n"
                                    + (document == null || document.name() == null
                                            ? ""
                                            : document.name()))
                            .toLowerCase(Locale.ROOT);
            for (String topic : topics) {
                if (title.contains(topic)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 正文侧门禁：主题词在已回表命中中累计出现 ≥3 次才算收录。 */
    private static boolean mentionsTopicInContents(
            List<String> topics, Map<Long, String> contents) {
        if (contents.isEmpty()) {
            return false;
        }
        Map<String, Integer> mentions = new HashMap<>();
        for (String content : contents.values()) {
            String lower = (content == null ? "" : content).toLowerCase(Locale.ROOT);
            for (String topic : topics) {
                mentions.merge(topic, countOccurrences(lower, topic), Integer::sum);
            }
        }
        return mentions.values().stream().anyMatch(count -> count >= MIN_TOPIC_MENTIONS);
    }

    private Map<String, Object> diagnostics(
            boolean fusion, int fused, int deduped, Map<String, Object> routeStats) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("fusion", fusion);
        diagnostics.put("rrf_k", settings.rrfK());
        diagnostics.put("routes", routeStats);
        diagnostics.put("fused", fused);
        diagnostics.put("deduped", deduped);
        diagnostics.put("max_per_document", settings.maxPerDocument());
        return diagnostics;
    }

    private static Map<String, Object> routeStat(int candidates, Long tookMs) {
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("candidates", candidates);
        stat.put("took_ms", tookMs == null ? 0L : tookMs);
        return stat;
    }

    private RouteHits vectorRoute(
            long seriesId,
            float[] queryVector,
            List<Chunk> candidates,
            Map<Long, Chunk> byId,
            Map<Long, Document> readyDocuments,
            double threshold,
            int limit) {
        if (queryVector == null) {
            return RouteHits.empty();
        }
        boolean degraded = false;
        if (elasticsearchService.available()) {
            List<RouteHit> hits = new ArrayList<>();
            boolean failed = false;
            try {
                for (KbElasticsearchService.SearchHit hit :
                        elasticsearchService.search(seriesId, queryVector, null, limit, false, 1.0)) {
                    Chunk chunk = byId.get(hit.chunkId());
                    if (chunk == null || !valid(chunk, readyDocuments)) {
                        continue;
                    }
                    double score = Math.max(0, Math.min(1, hit.vectorScore()));
                    if (score >= threshold) {
                        hits.add(new RouteHit(chunk, score));
                    }
                }
            } catch (RuntimeException e) {
                failed = true;
                log.warn("[知识库] ES 向量召回失败，本次降级关键词：{}", e.getMessage());
            }
            if (!failed) {
                if (!hits.isEmpty()) {
                    hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
                    return new RouteHits(limit(hits, limit), true, false);
                }
                return RouteHits.local(List.of());
            }
            degraded = true;
        }
        // ES 未配置时的离线兜底：仅对已截断的候选加载向量，避免大库全量扫描。
        Map<Long, float[]> vectors = loadVectors(candidates);
        List<RouteHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double score = Math.max(0, cosine(queryVector, vectors.get(chunk.id())));
            if (score >= threshold) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false, degraded);
    }

    private RouteHits bm25Route(
            String query,
            List<Chunk> candidates,
            Map<Long, Chunk> byId,
            KeywordHits keywords,
            boolean esBm25,
            int limit,
            Map<String, List<String>> tokenCache) {
        if (esBm25) {
            return bm25RouteEs(keywords, byId, limit);
        }
        return bm25RouteLocal(query, candidates, limit, tokenCache);
    }

    /** ES 关键词召回复用 multi_match（content^2 + titlePath），按原始最高分归一到 [0,1]。 */
    private RouteHits bm25RouteEs(KeywordHits keywords, Map<Long, Chunk> byId, int limit) {
        if (keywords.degraded()) {
            return new RouteHits(List.of(), false, true);
        }
        List<KbElasticsearchService.SearchHit> raw = keywords.hits();
        if (raw.isEmpty()) {
            return RouteHits.empty();
        }
        double max =
                raw.stream()
                        .mapToDouble(KbElasticsearchService.SearchHit::keywordScore)
                        .max()
                        .orElse(0);
        if (max <= 0) {
            return RouteHits.empty();
        }
        List<RouteHit> hits = new ArrayList<>();
        for (KbElasticsearchService.SearchHit hit : raw) {
            Chunk chunk = byId.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }
            double score = hit.keywordScore() / max;
            if (score >= settings.keywordThreshold()) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false, false);
    }

    private RouteHits bm25RouteLocal(
            String query, List<Chunk> candidates, int limit, Map<String, List<String>> tokenCache) {
        List<String> queryTokens = tokensCached(query, tokenCache);
        if (queryTokens.isEmpty()) {
            return RouteHits.empty();
        }
        Map<Long, List<String>> chunkTokens = new HashMap<>();
        for (Chunk chunk : candidates) {
            chunkTokens.put(chunk.id(), tokensCached(chunk.content(), tokenCache));
        }
        Map<Long, Double> scores = bm25(queryTokens, candidates, chunkTokens);
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (max <= 0) {
            return RouteHits.empty();
        }
        List<RouteHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double score = scores.getOrDefault(chunk.id(), 0.0) / max;
            if (score >= settings.keywordThreshold()) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false, false);
    }

    /** 标题/文档名命中：查询词覆盖率高，或整串命中时给小幅加成。 */
    private RouteHits titleRoute(
            String query,
            List<Chunk> candidates,
            Map<Long, Document> documents,
            int limit,
            Map<String, List<String>> tokenCache) {
        Set<String> queryTokens = new HashSet<>(tokensCached(query, tokenCache));
        if (queryTokens.isEmpty()) {
            return RouteHits.empty();
        }
        String queryLower = query.toLowerCase(Locale.ROOT);
        List<RouteHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            Document document = documents.get(chunk.documentId());
            String title =
                    ((chunk.titlePath() == null ? "" : chunk.titlePath())
                                    + " "
                                    + (document == null || document.name() == null
                                            ? ""
                                            : document.name()))
                            .trim();
            if (title.isEmpty()) {
                continue;
            }
            Set<String> titleTokens = new HashSet<>(tokensCached(title, tokenCache));
            long matched = queryTokens.stream().filter(titleTokens::contains).count();
            double coverage = (double) matched / queryTokens.size();
            double bonus = title.toLowerCase(Locale.ROOT).contains(queryLower) ? 0.1 : 0;
            double score = Math.min(1, coverage + bonus);
            if (score > 0 && score >= settings.titleThreshold()) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false, false);
    }

    private void addRoute(
            Map<Long, AccumulatedHit> accumulated,
            Route route,
            List<RouteHit> hits,
            double weight) {
        if (weight <= 0 || hits.isEmpty()) {
            return;
        }
        int rank = 0;
        for (RouteHit hit : hits) {
            rank++;
            accumulated
                    .computeIfAbsent(hit.chunk().id(), id -> new AccumulatedHit(hit.chunk()))
                    .add(route, hit.score(), rank, weight, settings.rrfK());
        }
    }

    private static List<RouteHit> limit(List<RouteHit> hits, int limit) {
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private DedupeResult dedupeByFingerprint(List<ScoredHit> hits) {
        Map<String, ScoredHit> unique = new LinkedHashMap<>();
        int removed = 0;
        for (ScoredHit hit : hits) {
            String key = hit.chunk().documentId() + "|" + fingerprint(hit.content());
            ScoredHit existing = unique.get(key);
            if (existing == null) {
                unique.put(key, hit);
            } else {
                unique.put(key, merge(existing, hit, Integer.MAX_VALUE));
                removed++;
            }
        }
        return new DedupeResult(new ArrayList<>(unique.values()), removed);
    }

    private DedupeResult adjacentMerge(
            List<ScoredHit> hits, Map<String, List<String>> tokenCache) {
        Map<String, List<ScoredHit>> groups = new LinkedHashMap<>();
        for (ScoredHit hit : hits) {
            String key =
                    hit.chunk().documentId()
                            + "|"
                            + (hit.chunk().titlePath() == null ? "" : hit.chunk().titlePath());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        Map<Long, ScoredHit> representatives = new LinkedHashMap<>();
        for (ScoredHit hit : hits) {
            representatives.put(hit.chunk().id(), hit);
        }
        int maxChunks = settings.maxMergeChunks();
        int removed = 0;
        for (List<ScoredHit> group : groups.values()) {
            group.sort(Comparator.comparingInt(hit -> hit.chunk().seq()));
            Set<Long> absorbed = new HashSet<>();
            ScoredHit current = null;
            int lastSeq = 0;
            for (ScoredHit hit : group) {
                if (absorbed.contains(hit.chunk().id())) {
                    continue;
                }
                if (current == null) {
                    current = hit;
                    lastSeq = hit.chunk().seq();
                    continue;
                }
                boolean adjacent = lastSeq + 1 == hit.chunk().seq();
                boolean canMerge =
                        adjacent
                                && current.mergedChunks() < maxChunks
                                && overlap(current.content(), hit.content(), tokenCache)
                                        > ADJACENT_OVERLAP;
                if (canMerge) {
                    current = merge(current, hit, maxChunks);
                    representatives.put(current.chunk().id(), current);
                    representatives.remove(hit.chunk().id());
                    absorbed.add(hit.chunk().id());
                    lastSeq = hit.chunk().seq();
                    removed++;
                } else {
                    current = hit;
                    lastSeq = hit.chunk().seq();
                }
            }
        }
        List<ScoredHit> merged = new ArrayList<>(representatives.values());
        merged.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return new DedupeResult(merged, removed);
    }

    private static ScoredHit merge(ScoredHit first, ScoredHit next, int maxChunks) {
        Map<String, Integer> ranks = new LinkedHashMap<>(first.routeRanks());
        next.routeRanks().forEach((route, rank) -> ranks.merge(route, rank, Math::min));
        LinkedHashSet<String> routes = new LinkedHashSet<>(first.routes());
        routes.addAll(next.routes());
        String content =
                first.content().equals(next.content())
                        ? first.content()
                        : first.content() + "\n" + next.content();
        return new ScoredHit(
                first.chunk(),
                Math.max(first.score(), next.score()),
                Math.max(first.vectorScore(), next.vectorScore()),
                Math.max(first.keywordScore(), next.keywordScore()),
                content,
                List.copyOf(routes),
                ranks,
                Math.min(maxChunks, first.mergedChunks() + next.mergedChunks()));
    }

    private List<ScoredHit> capPerDocument(List<ScoredHit> hits) {
        Map<Long, Integer> counts = new HashMap<>();
        List<ScoredHit> capped = new ArrayList<>();
        for (ScoredHit hit : hits) {
            int count = counts.merge(hit.chunk().documentId(), 1, Integer::sum);
            if (count <= settings.maxPerDocument()) {
                capped.add(hit);
            }
        }
        return capped;
    }

    private List<ScoredHit> fromElasticsearch(
            List<KbElasticsearchService.SearchHit> esHits, Map<Long, Document> readyDocuments) {
        List<Long> ids =
                esHits.stream().map(KbElasticsearchService.SearchHit::chunkId).distinct().toList();
        Map<Long, Chunk> chunks = new HashMap<>();
        for (Chunk chunk : chunkStore.findByIds(ids)) {
            chunks.put(chunk.id(), chunk);
        }
        List<ScoredHit> hits = new ArrayList<>();
        for (KbElasticsearchService.SearchHit hit : esHits) {
            Chunk chunk = chunks.get(hit.chunkId());
            if (!valid(chunk, readyDocuments)) {
                continue;
            }
            hits.add(
                    new ScoredHit(
                            chunk, hit.score(), hit.vectorScore(), hit.keywordScore()));
        }
        hits.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return hits;
    }

    private List<ScoredHit> searchLocally(
            String query,
            float[] queryVector,
            List<Chunk> candidates,
            boolean hybrid,
            RetrievalConfig config,
            Map<String, List<String>> tokenCache) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, float[]> vectors = loadVectors(candidates);
        List<String> queryTokens = tokensCached(query, tokenCache);
        Map<Long, List<String>> chunkTokens = new HashMap<>();
        for (Chunk chunk : candidates) {
            chunkTokens.put(chunk.id(), tokensCached(chunk.content(), tokenCache));
        }
        Map<Long, Double> bm25 = hybrid ? bm25(queryTokens, candidates, chunkTokens) : Map.of();
        double maxBm25 = bm25.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double vectorWeight = Math.max(0, Math.min(1, config.vectorWeight));
        List<ScoredHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double cosine = cosine(queryVector, vectors.get(chunk.id()));
            double vectorScore = Math.max(0, cosine);
            double keywordScore = maxBm25 == 0 ? 0 : bm25.getOrDefault(chunk.id(), 0.0) / maxBm25;
            double score =
                    hybrid
                            ? vectorWeight * vectorScore + (1 - vectorWeight) * keywordScore
                            : vectorScore;
            hits.add(new ScoredHit(chunk, score, vectorScore, keywordScore));
        }
        hits.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return hits;
    }

    private List<ScoredHit> rerank(
            String query, List<ScoredHit> hits, Map<String, List<String>> tokenCache) {
        Set<String> queryTokens = new HashSet<>(tokensCached(query, tokenCache));
        String queryLower = query.toLowerCase(Locale.ROOT);
        List<ScoredHit> reranked = new ArrayList<>(hits.size());
        for (ScoredHit hit : hits) {
            Set<String> tokens = new HashSet<>(tokensCached(hit.content(), tokenCache));
            long matched = tokens.stream().filter(queryTokens::contains).count();
            double coverage = queryTokens.isEmpty() ? 0 : (double) matched / queryTokens.size();
            double titleBonus =
                    hit.chunk().titlePath() != null
                                    && hit.chunk().titlePath().toLowerCase(Locale.ROOT).contains(queryLower)
                            ? 0.05
                            : 0;
            double score = Math.min(1, 0.7 * hit.score() + 0.3 * coverage + titleBonus);
            reranked.add(
                    new ScoredHit(
                            hit.chunk(),
                            score,
                            hit.vectorScore(),
                            hit.keywordScore(),
                            hit.content(),
                            hit.routes(),
                            hit.routeRanks(),
                            hit.mergedChunks()));
        }
        reranked.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return reranked;
    }

    private List<RetrievalHit> citations(
            List<ScoredHit> hits, Map<Long, Document> documents) {
        Set<Long> parentIds = new LinkedHashSet<>();
        for (ScoredHit hit : hits) {
            if (hit.chunk().parentChunkId() != null) {
                parentIds.add(hit.chunk().parentChunkId());
            }
        }
        Map<Long, Chunk> parents = new HashMap<>();
        for (Chunk chunk : chunkStore.findByIds(parentIds)) {
            parents.put(chunk.id(), chunk);
        }
        List<RetrievalHit> citations = new ArrayList<>(hits.size());
        int index = 0;
        for (ScoredHit hit : hits) {
            Chunk chunk = hit.chunk();
            Document document = documents.get(chunk.documentId());
            Chunk parent = chunk.parentChunkId() == null ? null : parents.get(chunk.parentChunkId());
            citations.add(
                    new RetrievalHit(
                            chunk.id(),
                            chunk.documentId(),
                            document == null ? null : document.name(),
                            round(hit.score()),
                            round(Math.max(0, hit.vectorScore())),
                            round(Math.max(0, hit.keywordScore())),
                            hit.content(),
                            parent == null
                                    ? null
                                    : truncate(parent.content(), MAX_PARENT_CONTENT),
                            chunk.titlePath(),
                            chunk.pageNo(),
                            "K" + (++index),
                            hit.routes(),
                            hit.routeRanks(),
                            hit.mergedChunks(),
                            chunk.seq(),
                            document == null ? null : document.configVersionId()));
        }
        return citations;
    }

    private Map<Long, Document> readyDocuments(long seriesId) {
        Map<Long, Document> documents = new LinkedHashMap<>();
        for (Document document : documentStore.findReadyBySeries(seriesId)) {
            if (document.status() == DocStatus.READY && document.deletedAt() == null) {
                documents.put(document.id(), document);
            }
        }
        return documents;
    }

    private static boolean valid(Chunk chunk, Map<Long, Document> readyDocuments) {
        return chunk != null
                && chunk.level() != ChunkLevel.PARENT
                && "ACTIVE".equals(chunk.status())
                && readyDocuments.containsKey(chunk.documentId());
    }

    private RetrievalResult empty(
            String query, String strategy, long started, boolean vectorEnabled, boolean hybrid) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("engine", "NONE");
        diagnostics.put("hybrid", hybrid);
        diagnostics.put("fusion", settings.fusionEnabled());
        diagnostics.put("reason", "当前知识库没有可检索的已就绪文档");
        return new RetrievalResult(
                query,
                strategy,
                System.currentTimeMillis() - started,
                List.of(),
                null,
                vectorEnabled,
                diagnostics);
    }

    private Map<Long, Double> bm25(
            List<String> queryTokens, List<Chunk> chunks, Map<Long, List<String>> chunkTokens) {
        int total = chunks.size();
        double avgLength =
                chunks.stream().mapToInt(chunk -> chunk.content().length()).average().orElse(1);
        Map<String, Long> documentFrequency = new HashMap<>();
        for (String token : new HashSet<>(queryTokens)) {
            long df =
                    chunkTokens.values().stream().filter(tokens -> tokens.contains(token)).count();
            documentFrequency.put(token, df);
        }
        double k1 = 1.2;
        double b = 0.75;
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            List<String> tokens = chunkTokens.getOrDefault(chunk.id(), List.of());
            Map<String, Integer> frequencies = new HashMap<>();
            for (String token : tokens) {
                frequencies.merge(token, 1, Integer::sum);
            }
            double score = 0;
            for (String queryToken : new HashSet<>(queryTokens)) {
                int tf = frequencies.getOrDefault(queryToken, 0);
                if (tf == 0) {
                    continue;
                }
                long df = documentFrequency.getOrDefault(queryToken, 0L);
                double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
                double denominator =
                        tf + k1 * (1 - b + b * tokens.size() / Math.max(1, avgLength));
                score += idf * (tf * (k1 + 1)) / denominator;
            }
            scores.put(chunk.id(), score);
        }
        return scores;
    }

    /** 与哈希向量一致的切词：CJK 单字与相邻二元组、拉丁词。 */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        int previousCjk = -1;
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean cjk =
                    (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                            || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
            if (cjk) {
                if (latin.length() > 0) {
                    tokens.add(latin.toString());
                    latin.setLength(0);
                }
                tokens.add(new String(Character.toChars(codePoint)));
                if (previousCjk >= 0) {
                    tokens.add(
                            new String(Character.toChars(previousCjk))
                                    + new String(Character.toChars(codePoint)));
                }
                previousCjk = codePoint;
            } else if (Character.isLetterOrDigit(codePoint)) {
                latin.appendCodePoint(codePoint);
                previousCjk = -1;
            } else {
                if (latin.length() > 0) {
                    tokens.add(latin.toString());
                    latin.setLength(0);
                }
                previousCjk = -1;
            }
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString());
        }
        return tokens;
    }

    private static String fingerprint(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }
        return builder.toString();
    }

    /** 检索轮内复用分词结果：同一段文本只切一次，列表保持词频信息供 BM25 使用。 */
    private static List<String> tokensCached(String text, Map<String, List<String>> cache) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (cache == null) {
            return tokens(text);
        }
        List<String> cached = cache.get(text);
        if (cached == null) {
            cached = List.copyOf(tokens(text));
            cache.put(text, cached);
        }
        return cached;
    }

    private static double overlap(
            String left, String right, Map<String, List<String>> tokenCache) {
        Set<String> leftTokens = new HashSet<>(tokensCached(left, tokenCache));
        if (leftTokens.isEmpty()) {
            return 0;
        }
        Set<String> rightTokens = new HashSet<>(tokensCached(right, tokenCache));
        if (rightTokens.isEmpty()) {
            return 0;
        }
        long shared = leftTokens.stream().filter(rightTokens::contains).count();
        return (double) shared / Math.min(leftTokens.size(), rightTokens.size());
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class AccumulatedHit {

        private final Chunk chunk;
        private final Map<String, Integer> ranks = new LinkedHashMap<>();
        private double fused;
        private double vectorScore;
        private double keywordScore;

        private AccumulatedHit(Chunk chunk) {
            this.chunk = chunk;
        }

        private void add(Route route, double rawScore, int rank, double weight, int rrfK) {
            fused += weight / (rrfK + rank);
            ranks.merge(route.name(), rank, Math::min);
            switch (route) {
                case VECTOR -> vectorScore = Math.max(vectorScore, rawScore);
                case BM25 -> keywordScore = Math.max(keywordScore, rawScore);
                case TITLE_PATH -> {
                    // 标题路只参与融合名次，不覆盖正文 BM25 分。
                }
            }
        }

        private ScoredHit toScoredHit(double maxScore, Map<Long, String> contents) {
            double normalized = maxScore > 0 ? fused / maxScore : 0;
            String content = contents.get(chunk.id());
            if (content == null) {
                content = chunk.content();
            }
            return new ScoredHit(
                    chunk,
                    normalized,
                    vectorScore,
                    keywordScore,
                    content == null ? "" : content,
                    List.copyOf(ranks.keySet()),
                    ranks,
                    1);
        }
    }
}
