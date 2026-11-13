package io.agentscope.examples.payroll;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.examples.payroll.model.OllamaThinkingModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OllamaThinkingModelTest {

    @Test
    @DisplayName("Ollama 流式适配器透传 thinking 并保留工具调用")
    void streamsThinkingAndToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/chat",
                exchange -> {
                    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                    String ndjson =
                            String.join(
                                    "\n",
                                    """
                                    {"model":"fake","message":{"role":"assistant","content":"","thinking":"先核对员工，"}}
                                    """,
                                    """
                                    {"model":"fake","message":{"role":"assistant","content":"","thinking":"再调用工具。"}}
                                    """,
                                    """
                                    {"model":"fake","message":{"role":"assistant","content":"开始计算。","tool_calls":[{"function":{"name":"calculate_payroll","arguments":{"employee_id":"EMP001","period":"2026-10"}}}]}}
                                    """,
                                    """
                                    {"model":"fake","message":{"role":"assistant","content":""},"done":true}
                                    """);
                    byte[] body = ndjson.getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            OllamaThinkingModel model =
                    new OllamaThinkingModel(
                            "fake",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            OllamaOptions.builder()
                                    .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                                    .build());
            ToolSchema schema =
                    ToolSchema.builder()
                            .name("calculate_payroll")
                            .description("计算单人工资")
                            .parameters(Map.of("type", "object"))
                            .build();

            List<ChatResponse> responses =
                    model.stream(
                                    List.<Msg>of(new UserMessage("计算张伟工资")),
                                    List.of(schema),
                                    GenerateOptions.builder().build())
                            .timeout(Duration.ofSeconds(10))
                            .collectList()
                            .block();

            assertNotNull(responses);
            assertEquals(1, responses.stream().map(ChatResponse::getId).distinct().count());
            assertTrue(requestBody.get().contains("\"think\":true"));
            assertTrue(requestBody.get().contains("\"calculate_payroll\""));

            String thinking =
                    responses.stream()
                            .flatMap(response -> response.getContent().stream())
                            .filter(ThinkingBlock.class::isInstance)
                            .map(block -> ((ThinkingBlock) block).getThinking())
                            .collect(Collectors.joining());
            assertEquals("先核对员工，再调用工具。", thinking);

            ToolUseBlock toolUse =
                    responses.stream()
                            .flatMap(response -> response.getContent().stream())
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("calculate_payroll", toolUse.getName());
            assertEquals("EMP001", toolUse.getInput().get("employee_id"));
            assertEquals("2026-10", toolUse.getInput().get("period"));
        } finally {
            server.stop(0);
        }
    }
}
