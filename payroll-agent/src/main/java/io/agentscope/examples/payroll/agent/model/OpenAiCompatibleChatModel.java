package io.agentscope.examples.payroll.agent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelOutboundPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 最小 OpenAI 兼容 Chat Completions 客户端（SSE 流式）。DeepSeek、vLLM、OneAPI 等
 * 提供 {@code /v1/chat/completions} 的服务都可直接接入。
 *
 * <p>只实现本仓库用到的能力：文本增量、工具调用、usage、完成原因；多模态与结构化输出
 * 走文本约定，不在此处理。
 */
public final class OpenAiCompatibleChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final String modelName;
    private final String chatUrl;
    private final String apiKey;
    private final Integer maxTokens;
    private final Double temperature;
    private final int timeoutSeconds;
    private final ModelOutboundPolicy outboundPolicy;
    private final HttpClient http;

    public OpenAiCompatibleChatModel(
            String modelName,
            String baseUrl,
            String apiKey,
            Integer contextWindow,
            Integer maxTokens,
            Double temperature,
            int timeoutSeconds,
            ModelOutboundPolicy outboundPolicy) {
        this.modelName = modelName;
        this.chatUrl = chatUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.outboundPolicy = outboundPolicy;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.min(10, this.timeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        if (contextWindow != null && contextWindow > 0) {
            setContextWindowSize(contextWindow);
        }
    }

    public static String chatUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION, "OpenAI 兼容连接必须填写接入地址");
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        URI uri = URI.create(chatUrl);
        outboundPolicy.check(uri);
        String body = requestBody(messages, tools, options);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return Flux.defer(() -> streamEvents(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatResponse> streamEvents(HttpRequest request) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelConfigException(
                    ModelConfigException.UNREACHABLE,
                    "无法连接模型服务 " + hostOf(request) + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelConfigException(
                    ModelConfigException.UNREACHABLE, "连接模型服务被中断，请重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = readQuietly(response.body());
            throw new ModelConfigException(
                    ModelConfigException.UNREACHABLE,
                    "模型服务返回 HTTP "
                            + response.statusCode()
                            + (detail.isBlank() ? "" : "：" + truncate(detail, 300)));
        }
        Stream<String> lines =
                new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))
                        .lines();
        ToolCallState state = new ToolCallState();
        return Flux.fromStream(lines)
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .filter(payload -> !payload.isEmpty())
                .takeUntil("[DONE]"::equals)
                .concatMap(payload -> parseChunk(payload, state));
    }

    /** 解析单个 SSE data 负载；累计工具调用并仅在收到完成原因时产出 ToolUseBlock。 */
    private Flux<ChatResponse> parseChunk(String payload, ToolCallState state) {
        if ("[DONE]".equals(payload)) {
            return Flux.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (IOException e) {
            log.warn("[Model] 忽略无法解析的流式片段：{}", truncate(payload, 120));
            return Flux.empty();
        }
        String id = root.path("id").asText(UUID.randomUUID().toString());
        Instant start = Instant.now();
        List<ContentBlock> content = new ArrayList<>();
        String finishReason = null;
        Usage usage = null;

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            finishReason = choice.path("finish_reason").isNull() ? null : textOrNull(choice, "finish_reason");
            JsonNode delta = choice.path("delta");
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                content.add(TextBlock.builder().text(text).build());
            }
            state.collect(delta.path("tool_calls"));
        }
        JsonNode usageNode = root.path("usage");
        if (usageNode.isObject()) {
            usage =
                    new Usage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0));
        }
        if (finishReason != null && !"null".equals(finishReason)) {
            state.flush(content, finishReason);
        }
        if (content.isEmpty() && finishReason == null && usage == null) {
            return Flux.empty();
        }
        ChatResponse.Builder builder = ChatResponse.builder().id(id).content(content);
        if (usage != null) {
            builder.usage(
                    ChatUsage.builder()
                            .inputTokens(usage.inputTokens())
                            .outputTokens(usage.outputTokens())
                            .time(Duration.between(start, Instant.now()).toMillis() / 1000.0)
                            .build());
        }
        if (finishReason != null && !"null".equals(finishReason)) {
            builder.finishReason(finishReason);
        }
        return Flux.just(builder.build());
    }

    /** 单个请求内的工具调用累计器；不能做成模型字段，否则并发会话会串数据。 */
    private static final class ToolCallState {
        private final Map<Integer, ToolCallBuilder> pending = new LinkedHashMap<>();

        private void collect(JsonNode calls) {
            if (!calls.isArray()) {
                return;
            }
            for (JsonNode call : calls) {
                int index = call.path("index").asInt(0);
                ToolCallBuilder builder =
                        pending.computeIfAbsent(index, key -> new ToolCallBuilder());
                String id = textOrNull(call, "id");
                if (id != null && !id.isBlank()) {
                    builder.id = id;
                }
                JsonNode function = call.path("function");
                String name = textOrNull(function, "name");
                if (name != null && !name.isBlank()) {
                    builder.name = name;
                }
                String arguments = textOrNull(function, "arguments");
                if (arguments != null) {
                    builder.arguments.append(arguments);
                }
            }
        }

        private void flush(List<ContentBlock> content, String finishReason) {
            if (pending.isEmpty()) {
                return;
            }
            if (!"tool_calls".equals(finishReason) && !"stop".equals(finishReason)) {
                return;
            }
            List<Integer> indexes = new ArrayList<>(pending.keySet());
            indexes.sort(Integer::compareTo);
            for (Integer index : indexes) {
                ToolCallBuilder call = pending.get(index);
                if (call.name == null || call.name.isBlank()) {
                    continue;
                }
                String raw = call.arguments.toString();
                Map<String, Object> input = parseArguments(raw);
                content.add(
                        new ToolUseBlock(
                                call.id == null ? UUID.randomUUID().toString() : call.id,
                                call.name,
                                input,
                                raw.isBlank() ? "{}" : raw,
                                null));
            }
            pending.clear();
        }
    }

    private static Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            log.warn("[Model] 工具参数不是合法 JSON，按空参数处理：{}", truncate(raw, 120));
            return Map.of();
        }
    }

    private String requestBody(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", modelName);
        root.put("stream", true);
        ObjectNode streamOptions = root.putObject("stream_options");
        streamOptions.put("include_usage", true);
        ArrayNode array = root.putArray("messages");
        for (Msg message : messages) {
            array.add(toOpenAiMessage(message));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = root.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode wrapper = toolArray.addObject();
                wrapper.put("type", "function");
                ObjectNode function = wrapper.putObject("function");
                function.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    function.put("description", tool.getDescription());
                }
                function.set(
                        "parameters",
                        JSON.valueToTree(
                                tool.getParameters() == null
                                        ? Map.of("type", "object", "properties", Map.of())
                                        : tool.getParameters()));
            }
        }
        Double requestTemperature =
                options != null && options.getTemperature() != null
                        ? options.getTemperature()
                        : temperature;
        if (requestTemperature != null) {
            root.put("temperature", requestTemperature);
        }
        Integer requestMaxTokens =
                options != null
                        ? (options.getMaxCompletionTokens() != null
                                ? options.getMaxCompletionTokens()
                                : options.getMaxTokens())
                        : null;
        Integer effectiveMaxTokens = requestMaxTokens != null ? requestMaxTokens : maxTokens;
        if (effectiveMaxTokens != null && effectiveMaxTokens > 0) {
            root.put("max_tokens", effectiveMaxTokens);
        }
        if (options != null && Boolean.TRUE.equals(options.getStream())) {
            root.put("stream", true);
        }
        return root.toString();
    }

    private ObjectNode toOpenAiMessage(Msg message) {
        ObjectNode node = JSON.createObjectNode();
        if (message.getRole() == MsgRole.TOOL) {
            node.put("role", "tool");
            ToolResultBlock result = message.getFirstContentBlock(ToolResultBlock.class);
            if (result != null) {
                node.put("tool_call_id", result.getId() == null ? "" : result.getId());
                node.put("content", textOf(result.getOutput()));
            } else {
                node.put("content", textOf(message.getContent()));
            }
            return node;
        }
        node.put("role", message.getRole() == MsgRole.USER ? "user"
                : message.getRole() == MsgRole.SYSTEM ? "system" : "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = JSON.createArrayNode();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else if (block instanceof HintBlock hintBlock) {
                text.append(hintBlock.getHint());
            } else if (block instanceof ToolUseBlock toolUse) {
                ObjectNode call = toolCalls.addObject();
                call.put("id", toolUse.getId() == null ? UUID.randomUUID().toString() : toolUse.getId());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", toolUse.getName());
                function.put("arguments", argumentsJson(toolUse));
            }
        }
        node.put("content", text.toString());
        if (!toolCalls.isEmpty()) {
            node.set("tool_calls", toolCalls);
        }
        return node;
    }

    private static String argumentsJson(ToolUseBlock toolUse) {
        if (toolUse.getContent() != null && !toolUse.getContent().isBlank()) {
            return toolUse.getContent();
        }
        Map<String, Object> input = toolUse.getInput();
        try {
            return JSON.writeValueAsString(input == null ? Map.of() : input);
        } catch (IOException e) {
            return "{}";
        }
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        return blocks.stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock textBlock) {
                                return Stream.of(textBlock.getText());
                            }
                            if (block instanceof HintBlock hintBlock) {
                                return Stream.of(hintBlock.getHint());
                            }
                            if (block instanceof ToolResultBlock result) {
                                return Stream.of(textOf(result.getOutput()));
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String readQuietly(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String hostOf(HttpRequest request) {
        return request.uri().getHost();
    }

    private record Usage(int inputTokens, int outputTokens) {}

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
