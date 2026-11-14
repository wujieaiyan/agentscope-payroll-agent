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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 知识库检索：多路召回 + RRF 融合 + 知识点合并；ES 不可用时自动退化本地实现。 */
@Service
public class KbRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalService.class);

    private static final double ADJACENT_OVERLAP = 0.6;
    private static final int MAX_PARENT_CONTENT = 1200;

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

    private record RouteHits(List<RouteHit> hits, boolean elasticsearch) {
        static RouteHits empty() {
            return new RouteHits(List.of(), false);
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

        KbEmbeddingService.Embedding embedding = embeddingService.embed(List.of(query));
        float[] queryVector =
                embedding.vectors().isEmpty() ? null : embedding.vectors().get(0);
        String engine = "LOCAL";
        List<ScoredHit> hits;
        Map<String, Object> routeDiagnostics = new LinkedHashMap<>();

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
                            recallLimit);
            hits = outcome.hits();
            engine = outcome.engine();
            routeDiagnostics.putAll(outcome.diagnostics());
            if (config.rerankEnabled && !hits.isEmpty()) {
                hits = rerank(query, hits);
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
                            recallLimit);
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

    /** 融合关闭时的旧路径，保留用于灰度回滚。 */
    private LegacyOutcome legacy(
            long seriesId,
            String query,
            float[] queryVector,
            Map<Long, Document> readyDocuments,
            RetrievalConfig config,
            boolean hybrid,
            double threshold,
            int recallLimit) {
        String engine = "LOCAL";
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
            hits = searchLocally(seriesId, query, queryVector, readyDocuments, hybrid, config);
            engine = "LOCAL";
        }
        hits = hits.stream().filter(hit -> hit.score() >= threshold).toList();
        if (config.rerankEnabled && !hits.isEmpty()) {
            hits = rerank(query, hits);
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
            int recallLimit) {
        List<Chunk> candidates = new ArrayList<>();
        for (Chunk chunk : chunkStore.findActiveBySeries(seriesId)) {
            if (valid(chunk, readyDocuments)) {
                candidates.add(chunk);
            }
        }
        List<Route> routes = new ArrayList<>(settings.recallRoutes());
        if (!hybrid) {
            routes.removeIf(route -> route != Route.VECTOR);
        }
        if (candidates.isEmpty() || routes.isEmpty()) {
            Map<String, Object> emptyDiagnostics = diagnostics(true, 0, 0, Map.of());
            return new FusionOutcome(List.of(), "LOCAL", emptyDiagnostics);
        }

        Map<Long, Chunk> byId = new HashMap<>();
        for (Chunk chunk : candidates) {
            byId.put(chunk.id(), chunk);
        }
        Map<String, Object> routeStats = new LinkedHashMap<>();
        RouteHits vector = RouteHits.empty();
        if (routes.contains(Route.VECTOR)) {
            long routeStarted = System.currentTimeMillis();
            vector =
                    vectorRoute(
                            seriesId,
                            queryVector,
                            candidates,
                            byId,
                            readyDocuments,
                            threshold,
                            recallLimit);
            routeStats.put("VECTOR", routeStat(vector.hits().size(), routeStarted));
        }
        RouteHits bm25 = RouteHits.empty();
        if (routes.contains(Route.BM25)) {
            long routeStarted = System.currentTimeMillis();
            bm25 = bm25Route(query, candidates, recallLimit);
            routeStats.put("BM25", routeStat(bm25.hits().size(), routeStarted));
        }
        RouteHits title = RouteHits.empty();
        if (routes.contains(Route.TITLE_PATH)) {
            long routeStarted = System.currentTimeMillis();
            title = titleRoute(query, candidates, readyDocuments, recallLimit);
            routeStats.put("TITLE_PATH", routeStat(title.hits().size(), routeStarted));
        }

        Map<Long, AccumulatedHit> accumulated = new LinkedHashMap<>();
        addRoute(accumulated, Route.VECTOR, vector.hits(), settings.weight(Route.VECTOR));
        addRoute(accumulated, Route.BM25, bm25.hits(), settings.weight(Route.BM25));
        addRoute(accumulated, Route.TITLE_PATH, title.hits(), settings.weight(Route.TITLE_PATH));

        double maxScore = 0;
        for (AccumulatedHit hit : accumulated.values()) {
            maxScore = Math.max(maxScore, hit.fused);
        }
        List<ScoredHit> fused = new ArrayList<>(accumulated.size());
        for (AccumulatedHit hit : accumulated.values()) {
            fused.add(hit.toScoredHit(maxScore));
        }
        fused.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        int fusedCount = fused.size();

        DedupeResult deduped = dedupeByFingerprint(fused);
        DedupeResult merged = adjacentMerge(deduped.hits());
        List<ScoredHit> capped = capPerDocument(merged.hits());
        int removed = deduped.removed() + merged.removed();
        String engine = vector.elasticsearch() ? "ELASTICSEARCH" : "LOCAL";
        Map<String, Object> diagnostics = diagnostics(true, fusedCount, removed, routeStats);
        return new FusionOutcome(capped, engine, diagnostics);
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

    private static Map<String, Object> routeStat(int candidates, long started) {
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("candidates", candidates);
        stat.put("took_ms", System.currentTimeMillis() - started);
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
        if (elasticsearchService.available()) {
            List<RouteHit> hits = new ArrayList<>();
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
                log.warn("[知识库] ES 向量召回失败，退化本地余弦：{}", e.getMessage());
                hits.clear();
            }
            if (!hits.isEmpty()) {
                hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
                return new RouteHits(limit(hits, limit), true);
            }
        }
        List<RouteHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double score =
                    Math.max(0, cosine(queryVector, embeddingService.fromJson(chunk.vector())));
            if (score >= threshold) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false);
    }

    private RouteHits bm25Route(String query, List<Chunk> candidates, int limit) {
        List<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            return RouteHits.empty();
        }
        Map<Long, List<String>> chunkTokens = new HashMap<>();
        for (Chunk chunk : candidates) {
            chunkTokens.put(chunk.id(), tokens(chunk.content()));
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
        return new RouteHits(limit(hits, limit), false);
    }

    /** 标题/文档名命中：查询词覆盖率高，或整串命中时给小幅加成。 */
    private RouteHits titleRoute(
            String query,
            List<Chunk> candidates,
            Map<Long, Document> documents,
            int limit) {
        Set<String> queryTokens = new HashSet<>(tokens(query));
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
            Set<String> titleTokens = new HashSet<>(tokens(title));
            long matched = queryTokens.stream().filter(titleTokens::contains).count();
            double coverage = (double) matched / queryTokens.size();
            double bonus = title.toLowerCase(Locale.ROOT).contains(queryLower) ? 0.1 : 0;
            double score = Math.min(1, coverage + bonus);
            if (score > 0 && score >= settings.titleThreshold()) {
                hits.add(new RouteHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(RouteHit::score).reversed());
        return new RouteHits(limit(hits, limit), false);
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

    private DedupeResult adjacentMerge(List<ScoredHit> hits) {
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
                                && overlap(current.content(), hit.content()) > ADJACENT_OVERLAP;
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
            long seriesId,
            String query,
            float[] queryVector,
            Map<Long, Document> readyDocuments,
            boolean hybrid,
            RetrievalConfig config) {
        List<Chunk> candidates = new ArrayList<>();
        for (Chunk chunk : chunkStore.findActiveBySeries(seriesId)) {
            if (valid(chunk, readyDocuments)) {
                candidates.add(chunk);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = tokens(query);
        Map<Long, List<String>> chunkTokens = new HashMap<>();
        for (Chunk chunk : candidates) {
            chunkTokens.put(chunk.id(), tokens(chunk.content()));
        }
        Map<Long, Double> bm25 = hybrid ? bm25(queryTokens, candidates, chunkTokens) : Map.of();
        double maxBm25 = bm25.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double vectorWeight = Math.max(0, Math.min(1, config.vectorWeight));
        List<ScoredHit> hits = new ArrayList<>();
        for (Chunk chunk : candidates) {
            double cosine = cosine(queryVector, embeddingService.fromJson(chunk.vector()));
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

    private List<ScoredHit> rerank(String query, List<ScoredHit> hits) {
        Set<String> queryTokens = new HashSet<>(tokens(query));
        String queryLower = query.toLowerCase(Locale.ROOT);
        List<ScoredHit> reranked = new ArrayList<>(hits.size());
        for (ScoredHit hit : hits) {
            Set<String> tokens = new HashSet<>(tokens(hit.content()));
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
                            hit.mergedChunks()));
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

    private static double overlap(String left, String right) {
        Set<String> leftTokens = new HashSet<>(tokens(left));
        if (leftTokens.isEmpty()) {
            return 0;
        }
        Set<String> rightTokens = new HashSet<>(tokens(right));
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

        private ScoredHit toScoredHit(double maxScore) {
            double normalized = maxScore > 0 ? fused / maxScore : 0;
            return new ScoredHit(
                    chunk,
                    normalized,
                    vectorScore,
                    keywordScore,
                    chunk.content(),
                    List.copyOf(ranks.keySet()),
                    ranks,
                    1);
        }
    }
}
