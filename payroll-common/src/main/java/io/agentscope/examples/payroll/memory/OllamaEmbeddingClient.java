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
import java.util.List;

/** 调用 Ollama /api/embed 生成向量；服务不可用时抛异常，由上层降级。 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI embedUri;
    private final URI tagsUri;
    private final String model;
    private final int dim;
    private final Duration timeout;

    public OllamaEmbeddingClient(
            String baseUrl, String model, int dim, Duration timeout) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.embedUri = URI.create(base + "/api/embed");
        this.tagsUri = URI.create(base + "/api/tags");
        this.model = model;
        this.dim = dim;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
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
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);
            HttpRequest request =
                    HttpRequest.newBuilder(embedUri)
                            .timeout(timeout)
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
