package io.agentscope.examples.payroll.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 语义缓存：Redis 精确键 + Elasticsearch 向量近邻，仅用于稳定只读问答。
 *
 * <p>嵌入或 ES 不可用时整体禁用，Redis 缺失时退化为进程内精确缓存；
 * 任何异常都只降级为未命中，不阻断对话主链路。
 */
public final class SemanticCacheService implements AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);
    private static final String EXACT_PREFIX = "payroll:semcache:exact:";
    private static final String COSINE_SCRIPT = "cosineSimilarity(params.q, 'vector') + 1.0";
    private static final int MAX_ANSWER_CHARS = 4000;

    private final SemanticCacheSettings settings;
    private final EmbeddingClient embeddings;
    private final StringRedisTemplate redis;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient client;
    private final ExecutorService writer;
    private final Map<String, CachedAnswer> local =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, CachedAnswer> eldest) {
                            return size() > 40000;
                        }
                    });

    private SemanticCacheService(
            SemanticCacheSettings settings,
            EmbeddingClient embeddings,
            StringRedisTemplate redis,
            ElasticsearchTransport transport,
            ElasticsearchClient client,
            ExecutorService writer) {
        this.settings = settings;
        this.embeddings = embeddings;
        this.redis = redis;
        this.transport = transport;
        this.client = client;
        this.writer = writer;
    }

    /** 关闭状态：不连接 ES，不产生任何额外调用。 */
    public static AnswerCache disabled() {
        return AnswerCache.disabled();
    }

    /** 启动探测；嵌入模型或 ES 任一不可用时返回 disabled。 */
    public static AnswerCache start(
            SemanticCacheSettings settings, StringRedisTemplate redis) {
        if (settings == null || !settings.enabled()) {
            log.info("[语义缓存] 未启用（payroll.semantic-cache.enabled=false）");
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
                    "[语义缓存] 嵌入模型 {} 不可用，缓存禁用",
                    settings.embeddingModel());
            return disabled();
        }
        RestClientBuilder builder = RestClient.builder(HttpHost.create(settings.esUrl()));
        builder.setRequestConfigCallback(
                config -> config.setConnectTimeout(2000).setSocketTimeout(3000));
        if (settings.esUsername() != null && !settings.esUsername().isBlank()) {
            CredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(
                            settings.esUsername(), settings.esPassword()));
            builder.setHttpClientConfigCallback(
                    http -> http.setDefaultCredentialsProvider(credentials));
        }
        RestClient restClient = builder.build();
        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);
        try {
            if (!client.ping().value()) {
                log.warn("[语义缓存] Elasticsearch 不可达（{}），缓存禁用", settings.esUrl());
                transport.close();
                return disabled();
            }
            if (!client.indices().exists(e -> e.index(settings.index())).value()) {
                client.indices()
                        .create(
                                c ->
                                        c.index(settings.index())
                                                .withJson(new StringReader(mapping(settings))));
                log.info("[语义缓存] 已创建索引 {}（{} 维）", settings.index(), settings.embeddingDim());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[语义缓存] 初始化失败，缓存禁用：{}", e.getMessage());
            try {
                transport.close();
            } catch (IOException ignored) {
            }
            return disabled();
        }
        log.info(
                "[语义缓存] 已启用：index={}，阈值={}，TTL={}min，精确缓存={}",
                settings.index(),
                settings.threshold(),
                settings.ttlMinutes(),
                redis == null ? "进程内" : "Redis");
        return new SemanticCacheService(
                settings, embeddings, redis, transport, client, newWriter());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<Hit> lookup(String scope, String question) {
        if (scope == null || scope.isBlank() || question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String key = exactKey(scope, normalized);
        CachedAnswer exact = readExact(key);
        if (exact != null && !exact.expired()) {
            log.info("[语义缓存] 精确命中：scope={}，问题={}", shortScope(scope), preview(normalized));
            return Optional.of(new Hit(exact.answer(), "exact", 1.0));
        }
        long startedAt = System.currentTimeMillis();
        try {
            float[] vector = embeddings.embed(normalized);
            Map<String, Object> source = search(scope, vector);
            if (source == null) {
                return Optional.empty();
            }
            String answer = String.valueOf(source.get("answer"));
            double score = ((Number) source.getOrDefault("score", 0D)).doubleValue();
            writeExact(key, answer, "semantic", score);
            log.info(
                    "[语义缓存] 语义命中：score={}，问题={}，耗时={}ms",
                    String.format(Locale.ROOT, "%.3f", score),
                    preview(normalized),
                    System.currentTimeMillis() - startedAt);
            return Optional.of(new Hit(answer, "semantic", score));
        } catch (RuntimeException e) {
            log.warn("[语义缓存] 查询失败，按未命中处理：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String scope, String question, String answer) {
        if (scope == null || scope.isBlank() || question == null || question.isBlank()) {
            return;
        }
        if (answer == null || answer.isBlank() || answer.length() > MAX_ANSWER_CHARS) {
            return;
        }
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return;
        }
        writeExact(exactKey(scope, normalized), answer, "exact", 1.0);
        submitStore(scope, normalized, answer);
    }

    @Override
    public void invalidate(String scope, String question) {
        if (scope == null || question == null) {
            return;
        }
        String id = questionHash(scope, normalize(question));
        deleteExact(EXACT_PREFIX + id);
        try {
            client.delete(d -> d.index(settings.index()).id(id));
            log.info("[语义缓存] 已删除：{}", preview(question));
        } catch (IOException | RuntimeException e) {
            log.warn("[语义缓存] 删除失败：{}", e.getMessage());
        }
    }

    /** 启动时清理过期文档；失败只记日志。 */
    public int cleanupExpired() {
        try {
            var response =
                    client.deleteByQuery(
                            d ->
                                    d.index(settings.index())
                                            .refresh(true)
                                            .query(
                                                    q ->
                                                            q.range(
                                                                    r ->
                                                                            r.field("expires_at")
                                                                                    .lt(
                                                                                            JsonData
                                                                                                    .of(
                                                                                                            System
                                                                                                                    .currentTimeMillis())))));
            long deleted = response.deleted() == null ? 0L : response.deleted();
            if (deleted > 0) {
                log.info("[语义缓存] 清理过期条目 {} 条", deleted);
            }
            return (int) deleted;
        } catch (IOException | RuntimeException e) {
            log.warn("[语义缓存] 清理失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.shutdownNow();
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (IOException e) {
                log.debug("[语义缓存] 关闭 ES 客户端失败：{}", e.getMessage());
            }
        }
    }

    private void submitStore(String scope, String normalized, String answer) {
        Runnable task =
                () -> {
                    try {
                        float[] vector = embeddings.embed(normalized);
                        long now = System.currentTimeMillis();
                        Map<String, Object> document = new HashMap<>();
                        document.put("scope_key", scope);
                        document.put("question_hash", questionHash(scope, normalized));
                        document.put("question", normalized);
                        document.put("vector", toList(vector));
                        document.put("answer", answer);
                        document.put("created_at", now);
                        document.put("expires_at", now + Duration.ofMinutes(settings.ttlMinutes()).toMillis());
                        document.put("hit_count", 0);
                        client.index(
                                i ->
                                        i.index(settings.index())
                                                .id(questionHash(scope, normalized))
                                                .document(document));
                        log.info("[语义缓存] 已写入：scope={}，问题={}", shortScope(scope), preview(normalized));
                    } catch (IOException | RuntimeException e) {
                        log.warn("[语义缓存] 写入失败：{}", e.getMessage());
                    }
                };
        try {
            writer.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[语义缓存] 写入队列已满，本次丢弃");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> search(String scope, float[] vector) {
        long now = System.currentTimeMillis();
        Query filter =
                Query.of(
                        q ->
                                q.bool(
                                        b ->
                                                b.filter(
                                                                t ->
                                                                        t.term(
                                                                                x ->
                                                                                        x.field(
                                                                                                        "scope_key")
                                                                                                .value(
                                                                                                        scope)))
                                                        .filter(
                                                                r ->
                                                                        r.range(
                                                                                v ->
                                                                                        v.field(
                                                                                                        "expires_at")
                                                                                                .gt(
                                                                                                        JsonData
                                                                                                                .of(
                                                                                                                        now))))));
        Query query =
                Query.of(
                        q ->
                                q.scriptScore(
                                        s ->
                                                s.query(filter)
                                                        .script(
                                                                script ->
                                                                        script.inline(
                                                                                inline ->
                                                                                        inline.source(
                                                                                                        COSINE_SCRIPT)
                                                                                                .params(
                                                                                                        "q",
                                                                                                        JsonData
                                                                                                                .of(
                                                                                                                        toList(
                                                                                                                                vector)))))));
        SearchResponse<Map> response;
        try {
            response =
                    client.search(
                            s -> s.index(settings.index()).size(1).query(query), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("语义检索失败：" + e.getMessage(), e);
        }
        List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = response.hits().hits();
        if (hits.isEmpty()) {
            return null;
        }
        co.elastic.clients.elasticsearch.core.search.Hit<Map> hit = hits.get(0);
        double cosine = (hit.score() == null ? 0D : hit.score()) - 1.0;
        if (cosine < settings.threshold() || hit.source() == null) {
            return null;
        }
        Map<String, Object> source = new HashMap<>((Map<String, Object>) hit.source());
        source.put("score", cosine);
        return source;
    }

    private CachedAnswer readExact(String key) {
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                return value == null ? null : JsonSupport.fromJson(value, CachedAnswer.class);
            } catch (RuntimeException e) {
                log.warn("[语义缓存] Redis 读取失败，改用进程内缓存：{}", e.getMessage());
            }
        }
        CachedAnswer cached = local.get(key);
        return cached == null || cached.expired() ? null : cached;
    }

    private void writeExact(String key, String answer, String type, double score) {
        long expiresAt = System.currentTimeMillis() + Duration.ofMinutes(settings.ttlMinutes()).toMillis();
        CachedAnswer cached = new CachedAnswer(answer, type, score, expiresAt);
        if (redis != null) {
            try {
                redis.opsForValue()
                        .set(key, JsonSupport.toJson(cached), Duration.ofMinutes(settings.ttlMinutes()));
                return;
            } catch (RuntimeException e) {
                log.warn("[语义缓存] Redis 写入失败，改用进程内缓存：{}", e.getMessage());
            }
        }
        local.put(key, cached);
    }

    private void deleteExact(String key) {
        if (redis != null) {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                log.warn("[语义缓存] Redis 删除失败：{}", e.getMessage());
            }
        }
        local.remove(key);
    }

    private static String exactKey(String scope, String normalized) {
        return EXACT_PREFIX + questionHash(scope, normalized);
    }

    static String questionHash(String scope, String normalized) {
        return sha256(scope + "|" + normalize(normalized));
    }

    /** 归一化：全角转半角、去空白与标点、转小写。 */
    static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(question.length());
        for (char c : question.toCharArray()) {
            char current = c;
            if (current >= '\uFF01' && current <= '\uFF5E') {
                current = (char) (current - 0xFEE0);
            }
            if (current == '\u3000' || Character.isWhitespace(current)) {
                continue;
            }
            builder.append(current);
        }
        return builder.toString()
                .replaceAll("[\\p{Punct}，。！？、；：“”‘’（）《》【】…—]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String shortScope(String scope) {
        return scope.length() <= 24 ? scope : scope.substring(0, 24) + "…";
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String mapping(SemanticCacheSettings settings) {
        return """
               {
                 "mappings": {
                   "properties": {
                     "scope_key":     { "type": "keyword" },
                     "question_hash": { "type": "keyword" },
                     "question":      { "type": "text" },
                     "vector":        { "type": "dense_vector", "dims": %d, "index": true },
                     "answer":        { "type": "text" },
                     "created_at":    { "type": "date", "format": "epoch_millis" },
                     "expires_at":    { "type": "date", "format": "epoch_millis" },
                     "hit_count":     { "type": "integer" }
                   }
                 }
               }
               """
                .formatted(settings.embeddingDim());
    }

    private static ExecutorService newWriter() {
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(256),
                        runnable -> {
                            Thread thread = new Thread(runnable, "payroll-semcache-writer");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }

    /** 精确缓存的值；expiresAt 为毫秒时间戳，兼容 Redis 与进程内两种存储。 */
    public record CachedAnswer(String answer, String type, double score, long expiresAt) {

        boolean expired() {
            return expiresAt > 0 && expiresAt < System.currentTimeMillis();
        }
    }
}
