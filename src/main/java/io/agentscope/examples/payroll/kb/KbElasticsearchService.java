package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 知识库分块索引：复用本机 ES，使用独立索引与 cjk_bigram 分词。 */
@Service
public class KbElasticsearchService {

    private static final Logger log = LoggerFactory.getLogger(KbElasticsearchService.class);
    private static final long AVAILABILITY_TTL_MS = 30_000;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final KbEmbeddingService embeddingService;
    private final HttpClient http;
    private final String baseUrl;
    private final String index;
    private final String authHeader;
    private final boolean enabled;
    private final int dim;
    private final AtomicLong lastCheck = new AtomicLong(0);
    private volatile boolean available;
    private volatile boolean indexChecked;
    private final Object indexLock = new Object();

    public KbElasticsearchService(
            ObjectMapper objectMapper,
            KbEmbeddingService embeddingService,
            @Value("${payroll.kb.es-enabled:true}") boolean enabled,
            @Value("${payroll.kb.es-url:http://localhost:9200}") String esUrl,
            @Value("${payroll.kb.es-username:}") String username,
            @Value("${payroll.kb.es-password:}") String password,
            @Value("${payroll.kb.es-index:payroll-kb-chunks}") String index,
            @Value("${payroll.kb.embedding-dim:768}") int dim) {
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.enabled = enabled;
        this.baseUrl = esUrl == null ? "http://localhost:9200" : esUrl.trim().replaceAll("/+$", "");
        this.index = index == null || index.isBlank() ? "payroll-kb-chunks" : index;
        this.dim = Math.max(64, dim);
        this.authHeader =
                username == null || username.isBlank()
                        ? null
                        : "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                (username + ":" + (password == null ? "" : password))
                                                        .getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public record SearchHit(long chunkId, double score, double vectorScore, double keywordScore) {
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean available() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        long checked = lastCheck.get();
        if (now - checked < AVAILABILITY_TTL_MS) {
            return available;
        }
        if (!lastCheck.compareAndSet(checked, now)) {
            return available;
        }
        try {
            Response response = request("GET", "/", null, Duration.ofSeconds(2));
            available = response.status() / 100 == 2;
        } catch (RuntimeException e) {
            available = false;
        }
        return available;
    }

    public void ensureIndex() {
        if (indexChecked) {
            return;
        }
        synchronized (indexLock) {
            if (indexChecked) {
                return;
            }
            Response head = request("HEAD", "/" + index, null, SEARCH_TIMEOUT);
            if (head.status() == 404) {
                Response created = request("PUT", "/" + index, mapping(true), SEARCH_TIMEOUT);
                if (created.status() / 100 != 2) {
                    log.warn(
                            "[知识库] 创建 ES 索引失败（将退回标准分词）：status={}，body={}",
                            created.status(),
                            preview(created.body()));
                    Response fallback = request("PUT", "/" + index, mapping(false), SEARCH_TIMEOUT);
                    if (fallback.status() / 100 != 2) {
                        throw new IllegalStateException(
                                "创建知识库索引失败：HTTP " + fallback.status() + " " + preview(fallback.body()));
                    }
                }
            }
            indexChecked = true;
        }
    }

    public void indexChunks(
            long seriesId,
            long documentId,
            String documentName,
            String documentExt,
            List<Chunk> chunks) {
        if (!available() || chunks == null || chunks.isEmpty()) {
            return;
        }
        ensureIndex();
        StringBuilder body = new StringBuilder();
        int count = 0;
        for (Chunk chunk : chunks) {
            if (chunk.level() == ChunkLevel.PARENT) {
                continue;
            }
            float[] vector = embeddingService.fromJson(chunk.vector());
            if (vector == null || vector.length != dim) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunk.id());
            source.put("seriesId", seriesId);
            source.put("documentId", documentId);
            source.put("seq", chunk.seq());
            source.put("chunkLevel", chunk.level().name());
            source.put("content", chunk.content());
            source.put("titlePath", chunk.titlePath());
            source.put("pageNo", chunk.pageNo());
            source.put("status", chunk.status());
            source.put("documentName", documentName);
            source.put("documentExt", documentExt);
            source.put("vector", vector);
            try {
                body.append("{\"index\":{\"_index\":\"")
                        .append(index)
                        .append("\",\"_id\":\"")
                        .append(chunk.id())
                        .append("\"}}\n")
                        .append(objectMapper.writeValueAsString(source))
                        .append('\n');
                count++;
            } catch (IOException e) {
                throw new IllegalStateException("序列化索引文档失败：" + e.getMessage(), e);
            }
        }
        if (count == 0) {
            return;
        }
        Response response =
                request("POST", "/_bulk?refresh=true", body.toString(), Duration.ofSeconds(15));
        if (response.status() / 100 != 2 || containsBulkErrors(response.body())) {
            throw new IllegalStateException(
                    "写入知识库索引失败：HTTP " + response.status() + " " + preview(response.body()));
        }
    }

    public void deleteDocument(long documentId) {
        if (!available()) {
            return;
        }
        deleteByTerm("documentId", documentId);
    }

    public void deleteSeries(long seriesId) {
        if (!available()) {
            return;
        }
        deleteByTerm("seriesId", seriesId);
    }

    public List<SearchHit> search(
            long seriesId,
            float[] vector,
            String queryText,
            int topK,
            boolean hybrid,
            double vectorWeight) {
        if (!available() || topK <= 0) {
            return List.of();
        }
        Map<Long, SearchHit> merged = new LinkedHashMap<>();
        int size = Math.max(topK, 10);
        if (vector != null && vector.length == dim) {
            List<SearchHit> vectorHits = searchVector(seriesId, vector, size);
            for (SearchHit hit : vectorHits) {
                merged.put(hit.chunkId(), hit);
            }
        }
        if (hybrid && queryText != null && !queryText.isBlank()) {
            List<SearchHit> keywordHits = searchKeyword(seriesId, queryText, size);
            double maxKeyword = keywordHits.stream().mapToDouble(SearchHit::keywordScore).max().orElse(0);
            for (SearchHit keyword : keywordHits) {
                double normalized = maxKeyword > 0 ? keyword.keywordScore() / maxKeyword : 0;
                SearchHit existing = merged.get(keyword.chunkId());
                if (existing == null) {
                    merged.put(
                            keyword.chunkId(),
                            new SearchHit(keyword.chunkId(), normalized, 0, normalized));
                } else {
                    merged.put(
                            keyword.chunkId(),
                            new SearchHit(
                                    keyword.chunkId(),
                                    existing.vectorScore() * vectorWeight
                                            + normalized * (1 - vectorWeight),
                                    existing.vectorScore(),
                                    normalized));
                }
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        double weight = hybrid ? Math.max(0, Math.min(1, vectorWeight)) : 1.0;
        List<SearchHit> result = new ArrayList<>();
        for (SearchHit hit : merged.values()) {
            double keyword = hit.keywordScore();
            double score =
                    hit.vectorScore() > 0 || keyword == 0
                            ? hit.vectorScore() * weight + keyword * (1 - weight)
                            : keyword;
            result.add(new SearchHit(hit.chunkId(), Math.max(0, Math.min(1, score)), hit.vectorScore(), keyword));
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result.size() > topK ? result.subList(0, topK) : result;
    }

    private List<SearchHit> searchVector(long seriesId, float[] vector, int size) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("size", size);
        query.put(
                "query",
                Map.of(
                        "script_score",
                        Map.of(
                                "query",
                                Map.of(
                                        "bool",
                                        Map.of(
                                                "filter",
                                                List.of(
                                                        Map.of("term", Map.of("seriesId", seriesId)),
                                                        Map.of(
                                                                "terms",
                                                                Map.of(
                                                                        "chunkLevel",
                                                                        List.of("SINGLE", "CHILD")))))),
                                "script",
                                Map.of(
                                        "source",
                                        "cosineSimilarity(params.q, 'vector') + 1.0",
                                        "params",
                                        Map.of("q", toList(vector))))));
        try {
            Response response =
                    request(
                            "POST",
                            "/" + index + "/_search",
                            objectMapper.writeValueAsString(query),
                            SEARCH_TIMEOUT);
            if (response.status() / 100 != 2) {
                log.warn("[知识库] ES 向量检索失败：status={}，body={}", response.status(), preview(response.body()));
                return List.of();
            }
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : objectMapper.readTree(response.body()).path("hits").path("hits")) {
                long chunkId = hit.path("_source").path("chunkId").asLong();
                double cosine = hit.path("_score").asDouble(0) - 1.0;
                double normalized = Math.max(0, Math.min(1, (cosine + 1) / 2));
                hits.add(new SearchHit(chunkId, normalized, normalized, 0));
            }
            return hits;
        } catch (IOException e) {
            log.warn("[知识库] ES 向量检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchHit> searchKeyword(long seriesId, String queryText, int size) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("size", size);
        query.put(
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("seriesId", seriesId)),
                                        Map.of(
                                                "terms",
                                                Map.of(
                                                        "chunkLevel",
                                                        List.of("SINGLE", "CHILD")))),
                                "must",
                                List.of(
                                        Map.of(
                                                "multi_match",
                                                Map.of(
                                                        "query",
                                                        queryText,
                                                        "fields",
                                                        List.of("content^2", "titlePath"),
                                                        "type",
                                                        "best_fields"))))));
        try {
            Response response =
                    request(
                            "POST",
                            "/" + index + "/_search",
                            objectMapper.writeValueAsString(query),
                            SEARCH_TIMEOUT);
            if (response.status() / 100 != 2) {
                return List.of();
            }
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : objectMapper.readTree(response.body()).path("hits").path("hits")) {
                long chunkId = hit.path("_source").path("chunkId").asLong();
                double score = hit.path("_score").asDouble(0);
                hits.add(new SearchHit(chunkId, score, 0, score));
            }
            return hits;
        } catch (IOException e) {
            log.warn("[知识库] ES 关键词检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    private void deleteByTerm(String field, long value) {
        Map<String, Object> query = Map.of("query", Map.of("term", Map.of(field, value)));
        try {
            Response response =
                    request(
                            "POST",
                            "/" + index + "/_delete_by_query?refresh=true&conflicts=proceed",
                            objectMapper.writeValueAsString(query),
                            Duration.ofSeconds(10));
            if (response.status() / 100 != 2 && response.status() != 404) {
                log.warn("[知识库] 清理索引失败：status={}，body={}", response.status(), preview(response.body()));
            }
        } catch (IOException e) {
            log.warn("[知识库] 清理索引失败：{}", e.getMessage());
        }
    }

    private String mapping(boolean cjk) {
        String analyzerPart =
                cjk
                        ? """
                          "settings": {
                            "analysis": {
                              "analyzer": {
                                "kb_text": {
                                  "type": "custom",
                                  "tokenizer": "standard",
                                  "filter": ["lowercase", "cjk_bigram"]
                                }
                              }
                            }
                          },
                          """
                        : "";
        String analyzer = cjk ? "kb_text" : "standard";
        return """
               {
                 %s
                 "mappings": {
                   "properties": {
                     "chunkId":      { "type": "long" },
                     "seriesId":     { "type": "long" },
                     "documentId":   { "type": "long" },
                     "seq":          { "type": "integer" },
                     "chunkLevel":   { "type": "keyword" },
                     "content":      { "type": "text", "analyzer": "%s" },
                     "titlePath":    { "type": "text", "analyzer": "%s" },
                     "pageNo":       { "type": "integer" },
                     "status":       { "type": "keyword" },
                     "documentName": { "type": "text" },
                     "documentExt":  { "type": "keyword" },
                     "vector":       { "type": "dense_vector", "dims": %d, "index": true }
                   }
                 }
               }
               """
                .formatted(analyzerPart, analyzer, analyzer, dim);
    }

    private boolean containsBulkErrors(String body) {
        try {
            return objectMapper.readTree(body).path("errors").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    private Response request(String method, String path, String body, Duration timeout) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json");
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        HttpRequest.BodyPublisher publisher =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("连接 Elasticsearch 失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Elasticsearch 请求被中断", e);
        }
    }

    private record Response(int status, String body) {
    }

    private List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }
}
