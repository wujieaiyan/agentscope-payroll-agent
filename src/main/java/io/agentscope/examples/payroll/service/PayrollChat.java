package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import java.util.List;

/** 管理台对话入口，便于在测试中替换为固定实现。 */
public interface PayrollChat extends AutoCloseable {

    Reply chat(String message);

    /**
     * 流式对话。默认实现退化为一次性回答，便于测试替身只实现 {@link #chat(String)}。
     */
    default void stream(String message, ChatStreamListener listener) {
        listener.onStart(modelId());
        Reply reply = chat(message);
        listener.onText(reply.reply());
        listener.onDone(reply);
    }

    default String modelId() {
        return PayrollAgentFactory.modelId();
    }

    default void reset() {
    }

    @Override
    default void close() {
    }

    /** 一次对话结果；tools 为本次回答调用过的工具名，traces 为逐次调用的依据快照。 */
    record Reply(
            String reply, List<String> tools, String model, List<ChatToolTrace> traces) {

        public Reply {
            tools = tools == null ? List.of() : List.copyOf(tools);
            traces = traces == null ? List.of() : List.copyOf(traces);
        }

        public Reply(String reply, List<String> tools, String model) {
            this(reply, tools, model, List.of());
        }
    }
}
