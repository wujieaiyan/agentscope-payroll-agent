package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 3B 模型偶发 0 token 空回复时的重试行为。 */
class EmptyReplyRetryTest {

    @Test
    @DisplayName("空回复且未调用工具时追加指令重试一次，空阶段不留在追溯里")
    void retriesOnceWhenModelReturnsEmptyText() throws Exception {
        EmptyThenTextModel model = new EmptyThenTextModel();
        HarnessAgent agent =
                HarnessAgentSupport.build(
                        "EmptyReplyAgent",
                        "空回复重试测试",
                        "基础提示词",
                        model,
                        new Toolkit(),
                        2,
                        null);
        StringBuilder answer = new StringBuilder();
        ChatStreamListener listener =
                new ChatStreamListener() {
                    @Override
                    public void onText(String delta) {
                        answer.append(delta);
                    }
                };
        PayrollChatService service =
                new PayrollChatService(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
        try {
            Method runAnswerStage =
                    PayrollChatService.class.getDeclaredMethod(
                            "runAnswerStage",
                            String.class,
                            String.class,
                            HarnessAgent.class,
                            String.class,
                            ChatStreamListener.class,
                            Set.class);
            runAnswerStage.setAccessible(true);
            runAnswerStage.invoke(
                    service,
                    "active",
                    "核算智能体：诗词专家",
                    agent,
                    "写一篇不少于 400 字的作文",
                    listener,
                    new LinkedHashSet<>());

            assertEquals(2, model.calls, "空回复后应自动重试一次");
            assertTrue(
                    model.secondUserText.contains("上一轮没有产生任何可见内容"),
                    "第二次请求应带明确的重试指令：" + model.secondUserText);
            assertEquals("重试后的正文", answer.toString());
            assertEquals(1, stages(service), "空回复尝试不应留下阶段记录");
        } finally {
            agent.close();
        }
    }

    private static int stages(PayrollChatService service) throws Exception {
        Field field = PayrollChatService.class.getDeclaredField("stageRuns");
        field.setAccessible(true);
        return ((List<?>) field.get(service)).size();
    }

    /** 第一次返回 0 token（模拟立即 EOS），之后正常输出正文。 */
    private static final class EmptyThenTextModel extends ChatModelBase {

        private volatile int calls;
        private volatile String secondUserText = "";

        @Override
        public String getModelName() {
            return "empty-then-text";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            if (calls == 1) {
                return Flux.just(
                        ChatResponse.builder().content(List.of()).finishReason("stop").build());
            }
            secondUserText =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.USER)
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("重试后的正文").build()))
                            .finishReason("stop")
                            .build());
        }
    }
}
