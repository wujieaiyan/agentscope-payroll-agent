package io.agentscope.examples.payroll.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch 7.17 实现：dense_vector + script_score 精确检索。
 *
 * <p>7.17 没有 8.x 的 knn 近似检索，但按 user_id 过滤后单用户语料很小，
 * 精确余弦检索足够；升级到 8.x 后只需替换查询部分。
 */
public final class ElasticsearchVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchVectorStore.class);

    private static final String COSINE_SCRIPT =
            "cosineSimilarity(params.q, 'vector') + 1.0";

    private final String index;
    private final int dim;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient client;

    public ElasticsearchVectorStore(
            String esUrl, String username, String password, String index, int dim) {
        this.index = index;
        this.dim = dim;
        RestClientBuilder builder = RestClient.builder(HttpHost.create(esUrl));
        builder.setRequestConfigCallback(
                config -> config.setConnectTimeout(2000).setSocketTimeout(3000));
        if (username != null && !username.isBlank()) {
            CredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(
                    AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(
                    http -> http.setDefaultCredentialsProvider(credentials));
        }
        RestClient restClient = builder.build();
        this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    @Override
    public void ensureIndex() {
        try {
            if (client.indices().exists(e -> e.index(index)).value()) {
                return;
            }
            client.indices().create(c -> c.index(index).withJson(new StringReader(mapping())));
            log.info("[向量] 已创建索引 {}（{} 维）", index, dim);
        } catch (IOException e) {
            throw new IllegalStateException("创建向量索引失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            return client.ping().value();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void index(VectorRecord record) {
        Map<String, Object> document = new HashMap<>();
        document.put("vector", toList(record.vector()));
        document.put("user_id", record.userId());
        document.put("session_id", record.sessionId());
        document.put("source_type", record.source().name());
        document.put("source_id", record.sourceId());
        document.put("turn_index", record.turnIndex());
        document.put("content", record.content());
        document.put("created_at", record.createdAt().toString());
        try {
            client.index(i -> i.index(index).id(documentId(record)).document(document));
        } catch (IOException e) {
            throw new IllegalStateException("写入向量文档失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorHit> search(
            long userId, float[] queryVector, int topK, double minCosine) {
        List<Float> query = toList(queryVector);
        SearchResponse<Map> response;
        try {
            response =
                    client.search(
                            s -> s.index(index).size(topK).query(vectorQuery(userId, query)),
                            Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("向量检索失败：" + e.getMessage(), e);
        }
        List<VectorHit> hits = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map source = hit.source();
            if (source == null) {
                continue;
            }
            double cosine = (hit.score() == null ? 0 : hit.score()) - 1.0;
            if (cosine < minCosine) {
                continue;
            }
            hits.add(
                    new VectorHit(
                            asLong(source.get("session_id")),
                            source.get("source_type") == null
                                    ? MemorySource.QUESTION
                                    : MemorySource.valueOf(source.get("source_type").toString()),
                            asLong(source.get("source_id")),
                            source.get("content") == null ? "" : source.get("content").toString(),
                            cosine,
                            asDate(source.get("created_at"))));
        }
        return hits;
    }

    @Override
    public void deleteSession(long userId, long sessionId) {
        Query query =
                Query.of(
                        q ->
                                q.bool(
                                        b ->
                                                b.filter(
                                                                t ->
                                                                        t.term(
                                                                                x ->
                                                                                        x.field(
                                                                                                        "user_id")
                                                                                                .value(
                                                                                                        userId)))
                                                        .filter(
                                                                t ->
                                                                        t.term(
                                                                                x ->
                                                                                        x.field(
                                                                                                        "session_id")
                                                                                                .value(
                                                                                                        sessionId)))));
        deleteByQuery(query);
    }

    @Override
    public void deleteSummaries(long sessionId) {
        Query query =
                Query.of(
                        q ->
                                q.bool(
                                        b ->
                                                b.filter(
                                                                t ->
                                                                        t.term(
                                                                                x ->
                                                                                        x.field(
                                                                                                        "session_id")
                                                                                                .value(
                                                                                                        sessionId)))
                                                        .filter(
                                                                t ->
                                                                        t.term(
                                                                                x ->
                                                                                        x.field(
                                                                                                        "source_type")
                                                                                                .value(
                                                                                                        MemorySource
                                                                                                                .SUMMARY
                                                                                                                .name())))));
        deleteByQuery(query);
    }

    @Override
    public void clearAll() {
        deleteByQuery(Query.of(q -> q.matchAll(m -> m)));
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException e) {
            log.debug("[向量] 关闭 ES 客户端失败：{}", e.getMessage());
        }
    }

    /** 幂等文档 ID：同一用户、会话、来源、轮次与内容生成同一 ID。 */
    static String documentId(VectorRecord record) {
        String raw =
                record.userId()
                        + ":"
                        + record.sessionId()
                        + ":"
                        + record.source().name()
                        + ":"
                        + record.turnIndex()
                        + ":"
                        + record.content();
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void deleteByQuery(Query query) {
        try {
            client.deleteByQuery(d -> d.index(index).refresh(true).query(query));
        } catch (IOException e) {
            throw new IllegalStateException("删除向量文档失败：" + e.getMessage(), e);
        }
    }

    private static Query vectorQuery(long userId, List<Float> query) {
        Query userFilter = Query.of(q -> q.term(t -> t.field("user_id").value(userId)));
        return Query.of(
                q ->
                        q.scriptScore(
                                s ->
                                        s.query(userFilter)
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
                                                                                                                query))))));
    }

    private String mapping() {
        return """
               {
                 "mappings": {
                   "properties": {
                     "vector":      { "type": "dense_vector", "dims": %d, "index": true },
                     "user_id":     { "type": "long" },
                     "session_id":  { "type": "long" },
                     "source_type": { "type": "keyword" },
                     "source_id":   { "type": "long" },
                     "turn_index":  { "type": "integer" },
                     "content":     { "type": "text" },
                     "created_at":  { "type": "date" }
                   }
                 }
               }
               """
                .formatted(dim);
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDate asDate(Object value) {
        if (value == null) {
            return LocalDate.now();
        }
        String text = value.toString();
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            return LocalDate.now();
        }
    }
}
