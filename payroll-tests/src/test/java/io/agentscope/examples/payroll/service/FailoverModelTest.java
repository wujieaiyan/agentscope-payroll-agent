package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.examples.payroll.agent.model.FailoverModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class FailoverModelTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        TurnMetrics.useRegistry(null);
    }

    @Test
    @DisplayName("首包连接失败时切换到备用连接并累计指标")
    void switchesOnFirstSignalConnectionFailure() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FailoverModel model =
                new FailoverModel(
                        failing(new ConnectException("拒绝连接")),
                        "控制面",
                        "primary",
                        () -> counting(fallbackCalls, Flux.just(response())));

        List<ChatResponse> result = model.stream(List.of(), List.of(), null).collectList().block();

        assertEquals(1, result.size());
        assertEquals(1, fallbackCalls.get());
        assertEquals(1.0, failoverCount("控制面"));
    }

    @Test
    @DisplayName("已产出内容后的连接中断不切换，避免重复输出")
    void keepsPartialOutputWhenFailureAfterFirstSignal() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        Model partialThenBroken =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.concat(
                                Flux.just(response()), Flux.error(new IOException("中断")));
                    }

                    @Override
                    public String getModelName() {
                        return "primary";
                    }
                };
        FailoverModel model =
                new FailoverModel(
                        partialThenBroken,
                        "控制面",
                        "primary",
                        () -> counting(fallbackCalls, Flux.just(response())));

        assertError(() -> model.stream(List.of(), List.of(), null).collectList().block());
        assertEquals(0, fallbackCalls.get());
        assertEquals(0.0, failoverCount("控制面"));
    }

    @Test
    @DisplayName("非连接类错误不触发切换")
    void ignoresNonConnectionFailures() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FailoverModel model =
                new FailoverModel(
                        failing(new IllegalStateException("模型返回格式错误")),
                        "控制面",
                        "primary",
                        () -> counting(fallbackCalls, Flux.just(response())));

        assertError(() -> model.stream(List.of(), List.of(), null).collectList().block());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    @DisplayName("备用连接失败直接上抛，不再尝试第三次")
    void doesNotRetryAfterFallbackFailure() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        FailoverModel model =
                new FailoverModel(
                        counting(primaryCalls, Flux.error(new ConnectException("主连接断开"))),
                        "控制面",
                        "primary",
                        () -> counting(fallbackCalls, Flux.error(new ConnectException("备用断开"))));

        assertError(() -> model.stream(List.of(), List.of(), null).collectList().block());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    @DisplayName("未配置备用连接时保留主连接错误")
    void keepsPrimaryErrorWithoutFallback() {
        FailoverModel model =
                new FailoverModel(
                        failing(new ConnectException("拒绝连接")), "轻量面", "primary", () -> null);

        assertError(() -> model.stream(List.of(), List.of(), null).collectList().block());
        assertEquals(0.0, failoverCount("轻量面"));
    }

    @Test
    @DisplayName("模型元信息委托主连接")
    void delegatesModelMetadata() {
        Model primary =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(response());
                    }

                    @Override
                    public String getModelName() {
                        return "deepseek-chat";
                    }

                    @Override
                    public int getContextWindowSize() {
                        return 65336;
                    }

                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };
        FailoverModel model = new FailoverModel(primary, "控制面", "primary", () -> null);

        assertEquals("deepseek-chat", model.getModelName());
        assertEquals(65336, model.getContextWindowSize());
        assertTrue(model.supportsNativeStructuredOutput());
        assertTrue(model.supportsNativeStructuredOutputWithTools());
    }

    private double failoverCount(String plane) {
        var counter = registry.find(TurnMetrics.MODEL_FAILOVER).tag("plane", plane).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static ChatResponse response() {
        return ChatResponse.builder().finishReason("stop").build();
    }

    private static Model failing(Throwable error) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(error);
            }

            @Override
            public String getModelName() {
                return "primary";
            }
        };
    }

    private static Model counting(AtomicInteger calls, Flux<ChatResponse> source) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(
                        () -> {
                            calls.incrementAndGet();
                            return source;
                        });
            }

            @Override
            public String getModelName() {
                return "counting";
            }
        };
    }

    private static void assertError(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("预期抛出异常但没有抛出");
    }
}
