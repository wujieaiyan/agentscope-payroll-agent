package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.memory.OllamaEmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库嵌入：优先 Ollama nomic-embed-text，缺失或超时时退化为同维度确定性哈希向量，
 * 并在 vector_source 中标记来源，保证离线环境仍可建库与检索。
 */
@Service
public class KbEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(KbEmbeddingService.class);
    private static final long AVAILABILITY_TTL_MS = 30_000;

    private final ObjectMapper objectMapper;
    private final OllamaEmbeddingClient client;
    private final int dim;
    private final AtomicLong lastCheck = new AtomicLong(0);
    private volatile boolean available;

    public KbEmbeddingService(
            ObjectMapper objectMapper,
            @Value("${payroll.kb.embedding-base-url:http://localhost:11434}") String baseUrl,
            @Value("${payroll.kb.embedding-model:nomic-embed-text}") String model,
            @Value("${payroll.kb.embedding-dim:768}") int dim) {
        this.objectMapper = objectMapper;
        this.dim = Math.max(64, dim);
        this.client =
                new OllamaEmbeddingClient(
                        baseUrl, model, this.dim, Duration.ofSeconds(15));
    }

    public record Embedding(List<float[]> vectors, String source, boolean ollama) {
    }

    public Embedding embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new Embedding(List.of(), "HASH", false);
        }
        if (available()) {
            try {
                return new Embedding(client.embedAll(texts), "OLLAMA", true);
            } catch (RuntimeException e) {
                log.warn("[知识库] Ollama 嵌入失败，退化为哈希向量：{}", e.getMessage());
                markUnavailable();
            }
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(hashVector(text));
        }
        return new Embedding(vectors, "HASH", false);
    }

    public boolean available() {
        long now = System.currentTimeMillis();
        long checked = lastCheck.get();
        if (now - checked < AVAILABILITY_TTL_MS) {
            return available;
        }
        if (!lastCheck.compareAndSet(checked, now)) {
            return available;
        }
        try {
            available = client.available();
        } catch (RuntimeException e) {
            available = false;
        }
        return available;
    }

    private void markUnavailable() {
        available = false;
        lastCheck.set(System.currentTimeMillis());
    }

    public int dim() {
        return dim;
    }

    public String toJson(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量序列化失败：" + e.getMessage(), e);
        }
    }

    public float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 词法哈希向量：CJK 单字与二元组、拉丁词各自散列到固定维度并做 L2 归一化。 */
    float[] hashVector(String text) {
        float[] vector = new float[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }
        List<String> tokens = new ArrayList<>();
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
                    tokens.add(new String(Character.toChars(previousCjk)) + new String(Character.toChars(codePoint)));
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
        for (String token : tokens) {
            long hash = stableHash(token);
            int index = (int) Math.floorMod(hash, dim);
            float sign = (hash & 1L) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    private static long stableHash(String token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xffL);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            return token.hashCode();
        }
    }
}
