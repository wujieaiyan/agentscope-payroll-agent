package io.agentscope.examples.payroll.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 Ollama /api/embed 生成向量；服务不可用时抛异常，由上层降级。 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 同一问题在 KB 预取与记忆召回中先后出现，短 TTL 共享向量可省一次嵌入调用。 */
    private static final long QUERY_CACHE_TTL_MS = 3_000L;
    private static final int QUERY_CACHE_MAX = 64;
    private static final Map<String, CachedVector> RECENT =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, CachedVector> eldest) {
                            return size() > QUERY_CACHE_MAX;
                        }
                    });

    private record CachedVector(float[] vector, long expiresAt) {
    }

    private final HttpClient http;
    private final URI embedUri;
    private final URI tagsUri;
    private final String model;
    private final int dim;
    private final Duration timeout;
    private final EmbeddingCircuitBreaker breaker;

    public OllamaEmbeddingClient(
            String baseUrl, String model, int dim, Duration timeout) {
        this(baseUrl, model, dim, timeout, null);
    }

    public OllamaEmbeddingClient(
            String baseUrl,
            String model,
            int dim,
            Duration timeout,
            EmbeddingCircuitBreaker breaker) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.embedUri = URI.create(base + "/api/embed");
        this.tagsUri = URI.create(base + "/api/tags");
        this.model = model;
        this.dim = dim;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        this.breaker = breaker;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = embedAll(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("嵌入服务未返回向量");
        }
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embedAll(texts, timeout);
    }

    /** 指定本次请求的预算；连接建立与读取合计不超过 {@code limit}。 */
    public List<float[]> embedAll(List<String> texts, Duration limit) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> cached = cachedVectors(texts);
        if (cached != null) {
            return cached;
        }
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);
            HttpRequest request =
                    HttpRequest.newBuilder(embedUri)
                            .timeout(limit == null ? timeout : limit)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(body),
                                            StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "嵌入服务返回 HTTP " + response.statusCode() + "：" + preview(response.body()));
            }
            JsonNode embeddings = JSON.readTree(response.body()).path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != texts.size()) {
                throw new IllegalStateException("嵌入服务返回的向量数量不匹配");
            }
            List<float[]> result = new ArrayList<>(embeddings.size());
            for (JsonNode node : embeddings) {
                float[] vector = toVector(node);
                if (vector.length != dim) {
                    throw new IllegalStateException(
                            "嵌入维度不匹配：期望 " + dim + "，实际 " + vector.length);
                }
                result.add(vector);
            }
            // HTTP 在锁外执行；回填前再查一次，同题并发时以先写入的缓存为准。
            List<float[]> racing = cachedVectors(texts);
            if (racing != null) {
                return racing;
            }
            cacheVectors(texts, result);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("嵌入请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("嵌入请求被中断", e);
        }
    }

    @Override
    public boolean available() {
        if (breaker != null && breaker.isOpen()) {
            return false;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(tagsUri)
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return false;
            }
            for (JsonNode node : JSON.readTree(response.body()).path("models")) {
                String name = node.path("name").asText("");
                if (name.equals(model) || name.startsWith(model + ":")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 仅当整批文本都在有效期内命中时直接返回，避免批量请求混用新旧向量。 */
    private List<float[]> cachedVectors(List<String> texts) {
        long now = System.currentTimeMillis();
        synchronized (RECENT) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                CachedVector cached = RECENT.get(cacheKey(text));
                if (cached == null || cached.expiresAt() < now) {
                    return null;
                }
                vectors.add(cached.vector());
            }
            return vectors;
        }
    }

    /** 只缓存少量短文本（问题向量），批量入库等大请求不入缓存以免挤掉近期问题。 */
    private void cacheVectors(List<String> texts, List<float[]> vectors) {
        if (texts.size() > 4) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + QUERY_CACHE_TTL_MS;
        synchronized (RECENT) {
            for (int i = 0; i < texts.size(); i++) {
                RECENT.put(cacheKey(texts.get(i)), new CachedVector(vectors.get(i), expiresAt));
            }
        }
    }

    private String cacheKey(String text) {
        return embedUri + "|" + model + "|" + text;
    }

    private static float[] toVector(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("嵌入向量格式错误");
        }
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
    }
}
