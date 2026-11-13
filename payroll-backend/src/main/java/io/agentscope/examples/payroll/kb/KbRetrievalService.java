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

/** 知识库检索：优先 ES 向量+关键词混合召回，不可用时退化为本地余弦 + BM25。 */
@Service
public class KbRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalService.class);

    private final KbChunkStore chunkStore;
    private final KbDocumentStore documentStore;
    private final KbConfigService configService;
    private final KbPermissionService permissionService;
    private final KbEmbeddingService embeddingService;
    private final KbElasticsearchService elasticsearchService;
    private final KbAnswerService answerService;

    public KbRetrievalService(
            KbChunkStore chunkStore,
            KbDocumentStore documentStore,
            KbConfigService configService,
            KbPermissionService permissionService,
            KbEmbeddingService embeddingService,
            KbElasticsearchService elasticsearchService,
            KbAnswerService answerService) {
        this.chunkStore = chunkStore;
        this.documentStore = documentStore;
        this.configService = configService;
        this.permissionService = permissionService;
        this.embeddingService = embeddingService;
        this.elasticsearchService = elasticsearchService;
        this.answerService = answerService;
    }

    private record ScoredHit(Chunk chunk, double score, double vectorScore, double keywordScore) {
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
        double minScore = threshold;
        hits = hits.stream().filter(hit -> hit.score() >= minScore).toList();
        if (config.rerankEnabled && !hits.isEmpty()) {
            hits = rerank(query, hits);
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
            Set<String> tokens = new HashSet<>(tokens(hit.chunk().content()));
            long matched = tokens.stream().filter(queryTokens::contains).count();
            double coverage = queryTokens.isEmpty() ? 0 : (double) matched / queryTokens.size();
            double titleBonus =
                    hit.chunk().titlePath() != null
                                    && hit.chunk().titlePath().toLowerCase(Locale.ROOT).contains(queryLower)
                            ? 0.05
                            : 0;
            double score = Math.min(1, 0.7 * hit.score() + 0.3 * coverage + titleBonus);
            reranked.add(
                    new ScoredHit(hit.chunk(), score, hit.vectorScore(), hit.keywordScore()));
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
                            chunk.content(),
                            parent == null ? null : truncate(parent.content(), 1200),
                            chunk.titlePath(),
                            chunk.pageNo()));
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
}
