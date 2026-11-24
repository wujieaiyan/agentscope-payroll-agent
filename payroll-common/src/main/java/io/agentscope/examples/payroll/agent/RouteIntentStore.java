package io.agentscope.examples.payroll.agent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.agentscope.examples.payroll.service.EsMetrics;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 语义意图索引：ES 7.17 无 knn 检索，90 条种子规模下用 script_score 精确余弦，结果确定可快照。
 *
 * <p>文档按 {@code label_version + model} 过滤，重建时清理其他版本文档，避免种子迭代后无限增长。
 */
final class RouteIntentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RouteIntentStore.class);
    private static final String COSINE_SCRIPT = "cosineSimilarity(params.q, 'vector') + 1.0";

    private final String index;
    private final int dim;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient client;

    RouteIntentStore(
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

    boolean ping() {
        try {
            return client.ping().value();
        } catch (IOException e) {
            return false;
        }
    }

    void ensureIndex() {
        try {
            if (client.indices().exists(e -> e.index(index)).value()) {
                return;
            }
            client.indices().create(c -> c.index(index).withJson(new StringReader(mapping())));
            log.info("[知识库][语义] 已创建意图索引 {}（{} 维）", index, dim);
        } catch (IOException e) {
            throw new IllegalStateException("创建意图索引失败：" + e.getMessage(), e);
        }
    }

    long count(String version, String model) {
        long startedAt = EsMetrics.start();
        try {
            return client.count(
                            c ->
                                    c.index(index)
                                            .query(
                                                    q ->
                                                            q.bool(
                                                                    b ->
                                                                            b.filter(term("label_version", version))
                                                                                    .filter(term("model", model)))))
                    .count();
        } catch (IOException e) {
            throw new IllegalStateException("统计意图索引失败：" + e.getMessage(), e);
        } finally {
            EsMetrics.record("search", startedAt);
        }
    }

    void replace(
            String version,
            String model,
            List<RouteIntentSeeds.Sample> samples,
            List<float[]> vectors) {
        if (samples.size() != vectors.size()) {
            throw new IllegalStateException("意图种子与向量数量不一致");
        }
        deleteStale(version, model);
        BulkResponse response;
        long startedAt = EsMetrics.start();
        try {
            response =
                    client.bulk(
                            b -> {
                                for (int i = 0; i < samples.size(); i++) {
                                    RouteIntentSeeds.Sample sample = samples.get(i);
                                    float[] vector = vectors.get(i);
                                    Map<String, Object> document = new HashMap<>();
                                    document.put("vector", toList(vector));
                                    document.put("label", sample.label());
                                    document.put("question", sample.question());
                                    document.put("label_version", version);
                                    document.put("model", model);
                                    String id = version + "-" + i;
                                    b.operations(
                                            op ->
                                                    op.index(
                                                            i2 ->
                                                                    i2.index(index)
                                                                            .id(id)
                                                                            .document(document)));
                                }
                                return b;
                            });
        } catch (IOException e) {
            throw new IllegalStateException("写入意图索引失败：" + e.getMessage(), e);
        } finally {
            EsMetrics.record("index", startedAt);
        }
        if (response.errors()) {
            throw new IllegalStateException("写入意图索引存在失败条目");
        }
        long refreshStartedAt = EsMetrics.start();
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new IllegalStateException("刷新意图索引失败：" + e.getMessage(), e);
        } finally {
            EsMetrics.record("refresh", refreshStartedAt);
        }
    }

    List<SemanticRouteClassifier.Hit> top(
            String version, String model, float[] vector, int limit) {
        SearchResponse<Map> response;
        long startedAt = EsMetrics.start();
        try {
            response =
                    client.search(
                            s ->
                                    s.index(index)
                                            .size(limit)
                                            .query(
                                                    q ->
                                                            q.scriptScore(
                                                                    ss ->
                                                                            ss.query(
                                                                                            f ->
                                                                                                    f.bool(
                                                                                                            b ->
                                                                                                                    b.filter(term("label_version", version))
                                                                                                                            .filter(term("model", model))))
                                                                                    .script(
                                                                                            sc ->
                                                                                                    sc.inline(
                                                                                                            in ->
                                                                                                                    in.source(COSINE_SCRIPT)
                                                                                                                            .params(
                                                                                                                                    "q",
                                                                                                                                    JsonData.of(
                                                                                                                                            toList(
                                                                                                                                                    vector))))))),
                            Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("语义意图检索失败：" + e.getMessage(), e);
        } finally {
            EsMetrics.record("search", startedAt);
        }
        List<SemanticRouteClassifier.Hit> hits = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map source = hit.source();
            if (source == null || source.get("label") == null) {
                continue;
            }
            double cosine = (hit.score() == null ? 0 : hit.score()) - 1.0;
            hits.add(new SemanticRouteClassifier.Hit(source.get("label").toString(), cosine));
        }
        return hits;
    }

    private void deleteStale(String version, String model) {
        Query keep =
                Query.of(
                        q ->
                                q.bool(
                                        b ->
                                                b.filter(term("label_version", version))
                                                        .filter(term("model", model))));
        long startedAt = EsMetrics.start();
        try {
            client.deleteByQuery(
                    d -> d.index(index).refresh(true).query(q -> q.bool(b -> b.mustNot(keep))));
        } catch (IOException e) {
            throw new IllegalStateException("清理旧意图索引失败：" + e.getMessage(), e);
        } finally {
            EsMetrics.record("delete", startedAt);
        }
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private String mapping() {
        return """
               {
                 "mappings": {
                   "properties": {
                     "vector":        { "type": "dense_vector", "dims": %d },
                     "label":         { "type": "keyword" },
                     "question":      { "type": "text" },
                     "label_version": { "type": "keyword" },
                     "model":         { "type": "keyword" }
                   }
                 }
               }
               """
                .formatted(dim);
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException e) {
            log.debug("[知识库][语义] 关闭意图索引客户端失败：{}", e.getMessage());
        }
    }
}
