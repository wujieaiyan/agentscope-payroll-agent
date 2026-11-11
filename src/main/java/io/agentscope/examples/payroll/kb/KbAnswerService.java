package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalHit;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 基于检索片段的问答生成：调用 Ollama /api/chat，要求按 [编号] 标注引用。 */
@Service
public class KbAnswerService {

    private static final Logger log = LoggerFactory.getLogger(KbAnswerService.class);
    private static final int MAX_CONTEXTS = 5;
    private static final int MAX_CONTEXT_CHARS = 6000;

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    public KbAnswerService(
            ObjectMapper objectMapper,
            @Value("${payroll.kb.answer-enabled:true}") boolean enabled,
            @Value("${payroll.kb.answer-base-url:${payroll.kb.embedding-base-url:http://localhost:11434}}")
                    String baseUrl,
            @Value("${payroll.kb.answer-model:qwen2.5:3b-instruct}") String model,
            @Value("${payroll.kb.answer-timeout:120s}") Duration timeout) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "http://localhost:11434" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model == null || model.isBlank() ? "qwen2.5:3b-instruct" : model;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record Answer(String text, String model, String error, long tookMs, boolean generated) {
        static Answer skipped(String model, String error) {
            return new Answer(null, model, error, 0, false);
        }
    }

    public Answer answer(String query, List<RetrievalHit> hits) {
        if (!enabled) {
            return Answer.skipped(model, "问答生成未启用");
        }
        if (hits == null || hits.isEmpty()) {
            return Answer.skipped(model, "没有可用于生成的检索片段");
        }
        long started = System.currentTimeMillis();
        List<RetrievalHit> selected = hits.size() > MAX_CONTEXTS ? hits.subList(0, MAX_CONTEXTS) : hits;
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        messages.add(Map.of("role", "user", "content", buildPrompt(query, selected)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        body.put("options", Map.of("num_ctx", 4096, "temperature", 0.2));
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body),
                                            StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("[知识库] 问答生成失败：HTTP {}，body={}", response.statusCode(), preview(response.body()));
                return new Answer(
                        null,
                        model,
                        "问答服务返回 HTTP " + response.statusCode(),
                        System.currentTimeMillis() - started,
                        false);
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                return new Answer(
                        null,
                        model,
                        "问答服务返回错误：" + root.path("error").asText(),
                        System.currentTimeMillis() - started,
                        false);
            }
            String content = root.path("message").path("content").asText("").trim();
            if (content.isEmpty()) {
                return new Answer(
                        null, model, "问答服务返回空回答", System.currentTimeMillis() - started, false);
            }
            long took = System.currentTimeMillis() - started;
            log.info(
                    "[知识库] 问答生成完成：model={}，片段={}，问题={}字，回答={}字，耗时={}ms",
                    model,
                    selected.size(),
                    query.length(),
                    content.length(),
                    took);
            return new Answer(content, model, null, took, true);
        } catch (IOException e) {
            log.warn("[知识库] 问答生成失败：{}", e.getMessage());
            return new Answer(
                    null, model, friendlyError(e), System.currentTimeMillis() - started, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(
                    null, model, "问答生成被中断", System.currentTimeMillis() - started, false);
        }
    }

    private String systemPrompt() {
        return "你是企业知识库问答助手。请严格依据用户提供的资料回答，不要编造资料中没有的信息；"
                + "资料不足时直接说明缺少哪些信息。引用资料时在相应句子末尾标注 [编号]。"
                + "使用简体中文，条理清晰，只输出纯文本，不要使用 Markdown 标题、表格或代码块。";
    }

    private String buildPrompt(String query, List<RetrievalHit> hits) {
        int perContext = Math.max(300, MAX_CONTEXT_CHARS / Math.max(1, hits.size()));
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：").append(query).append("\n\n资料：\n");
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            prompt.append('[').append(i + 1).append("] 文档：").append(nullToEmpty(hit.documentName()));
            if (hit.titlePath() != null && !hit.titlePath().isBlank()) {
                prompt.append(" | 路径：").append(hit.titlePath());
            }
            if (hit.pageNo() != null) {
                prompt.append(" | 第 ").append(hit.pageNo()).append(" 页");
            }
            String content =
                    hit.parentContent() != null && !hit.parentContent().isBlank()
                            ? hit.parentContent()
                            : hit.content();
            prompt.append('\n').append(truncate(content, perContext)).append("\n\n");
        }
        prompt.append("请基于以上资料回答问题，并在引用处标注来源编号。");
        return prompt.toString();
    }

    private String friendlyError(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("connect")) {
            return "无法连接问答服务（" + baseUrl + "），请先启动 Ollama";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "问答服务响应超时，请稍后重试";
        }
        return message.isBlank() ? "问答服务调用失败" : message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }
}
