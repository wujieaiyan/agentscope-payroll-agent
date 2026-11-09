package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import java.util.List;

/** 管理台对话入口，便于在测试中替换为固定实现。 */
public interface PayrollChat extends AutoCloseable {

    Reply chat(String message);

    /** 注入本轮团队运行坐标；未启用动态团队时为空实现。 */
    default void useRunScope(TraceScope scope) {
    }

    /** 本轮已执行/跳过的阶段摘要，供会话层落团队运行记录。 */
    default List<StageRun> stageRuns() {
        return List.of();
    }

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

    /** 单阶段运行摘要；由静态流水线或流程节点在结束时写入。 */
    record StageRun(
            String nodeId,
            String stageKey,
            String agentId,
            String role,
            String status,
            int toolCalls,
            int toolErrors,
            Boolean validationPassed,
            int outputChars,
            long durationMs,
            String errorMessage) {

        public StageRun {
            stageKey = stageKey == null ? "" : stageKey;
            nodeId = nodeId == null || nodeId.isBlank() ? stageKey : nodeId;
            status = status == null ? "SUCCESS" : status;
        }
    }
}
