package io.agentscope.examples.payroll.service;

/**
 * 对话流式回调：把 ReActAgent 的思考、工具调用与回答增量转成前端可渲染的事件。
 *
 * <p>回调在同一个对话线程内串行触发；实现不做耗时操作，避免阻塞模型流。
 */
public interface ChatStreamListener {

    /** 对话开始，返回当前模型标识。 */
    default void onStart(String model) {
    }

    /** 模型原生思考/推理增量（仅思考型模型会触发）。 */
    default void onThinking(String delta) {
    }

    /** 阶段提示：模型思考中、达到最大轮次等。 */
    default void onPhase(String label) {
    }

    /** 开始调用工具。 */
    default void onToolStart(String toolCallId, String toolName) {
    }

    /** 工具入参 JSON 增量。 */
    default void onToolArgs(String toolCallId, String delta) {
    }

    /** 工具执行结束，state 为 SUCCESS/ERROR/INTERRUPTED/DENIED 等。 */
    default void onToolResult(String toolCallId, String toolName, String state) {
    }

    /** 回答正文增量。 */
    default void onText(String delta) {
    }

    /** 本轮回答结束。 */
    default void onDone(PayrollChat.Reply reply) {
    }
}
