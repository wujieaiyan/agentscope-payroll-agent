package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.formatter.OllamaChatFormatter;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Ollama 流式模型适配器：在 AgentScope 2.0.1 官方适配层基础上补齐 {@code message.thinking}
 * 透传。
 *
 * <p>官方 {@code OllamaResponse} 未声明 thinking 字段，思考内容在反序列化时被丢弃；
 * 这里保留相同的请求构建与响应解析逻辑，额外把思考增量包装成 {@link ThinkingBlock}，
 * 使 ReActAgent 能发出 {@code ThinkingBlockDeltaEvent}。
 */
public final class OllamaThinkingModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OllamaThinkingModel.class);
    private static final String CHAT_ENDPOINT = "/api/chat";
    private static final String STREAM_FORMAT_HEADER = "X-AgentScope-Stream-Format";

    private final String modelName;
    private final String baseUrl;
    private final OllamaOptions defaultOptions;
    private final OllamaChatFormatter formatter = new OllamaChatFormatter();
    private final HttpTransport transport = HttpTransportFactory.getDefault();
    private final JsonCodec json = JsonUtils.getJsonCodec();

    public OllamaThinkingModel(String modelName, String baseUrl, OllamaOptions defaultOptions) {
        this.modelName = modelName;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.defaultOptions = defaultOptions;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions generateOptions) {
        OllamaOptions overrides = OllamaOptions.fromGenerateOptions(generateOptions);
        OllamaOptions effective = defaultOptions.merge(overrides);
        List<OllamaMessage> formatted = formatter.format(messages);
        OllamaRequest request =
                formatter.buildRequest(
                        modelName,
                        formatted,
                        true,
                        overrides,
                        defaultOptions,
                        tools,
                        generateOptions == null ? null : generateOptions.getToolChoice());
        if (effective.getThinkOption() != null && request.getThink() == null) {
            request.setThink(effective.getThinkOption().toJsonValue());
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "[ollama] 请求：模型={}，消息={}，工具={}，toolChoice={}",
                    modelName,
                    formatted.size(),
                    tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList(),
                    generateOptions == null ? null : generateOptions.getToolChoice());
        }
        if (log.isTraceEnabled()) {
            log.trace("[ollama] 请求体：{}", json.toJson(request));
        }

        HttpRequest httpRequest =
                HttpRequest.builder()
                        .url(baseUrl + CHAT_ENDPOINT)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header(STREAM_FORMAT_HEADER, "ndjson")
                        .body(json.toJson(request))
                        .build();

        String[] replyId = new String[1];
        Flux<ChatResponse> chunks =
                transport.stream(httpRequest)
                        .filter(line -> line != null && !line.isBlank())
                        .map(line -> parseChunk(line, replyId));
        return ModelUtils.applyTimeoutAndRetry(
                chunks,
                effective.toGenerateOptions(),
                defaultOptions.toGenerateOptions(),
                modelName,
                "ollama");
    }

    private ChatResponse parseChunk(String line, String[] replyId) {
        OllamaResponse response = json.fromJson(line, OllamaResponse.class);
        if (response.getError() != null && !response.getError().isBlank()) {
            throw new PayrollException("Ollama 返回错误：" + response.getError());
        }
        ChatResponse parsed = formatter.parseResponse(response, Instant.now());
        JsonNode thinkingNode = json.fromJson(line, JsonNode.class).path("message").path("thinking");
        if (!thinkingNode.isMissingNode() && !thinkingNode.asText().isEmpty()) {
            List<ContentBlock> blocks = new ArrayList<>(parsed.getContent().size() + 1);
            blocks.add(ThinkingBlock.builder().thinking(thinkingNode.asText()).build());
            blocks.addAll(parsed.getContent());
            parsed =
                    ChatResponse.builder()
                            .id(parsed.getId())
                            .content(blocks)
                            .usage(parsed.getUsage())
                            .metadata(parsed.getMetadata())
                            .finishReason(parsed.getFinishReason())
                            .build();
        }
        if (replyId[0] == null) {
            replyId[0] = parsed.getId();
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "[ollama] 响应：finish={}，内容块={}，原始长度={}",
                    parsed.getFinishReason(),
                    parsed.getContent().size(),
                    line.length());
        }
        if (log.isTraceEnabled()) {
            log.trace("[ollama] 响应体：{}", line);
        }
        return parsed.withId(replyId[0]);
    }
}
