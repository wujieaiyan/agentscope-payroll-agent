package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ChatToolTrace;

/**
 * 对话流式回调：把 ReActAgent 的思考、工具调用与回答增量转成前端可渲染的事件。
 *
 * <p>回调在同一个对话线程内串行触发；实现不做耗时操作，避免阻塞模型流。
 */
public interface ChatStreamListener {

    ChatStreamListener NOOP = new ChatStreamListener() {
    };

    /** 对话开始，返回当前模型标识。 */
    default void onStart(String model) {
    }

    /** 某一智能体阶段开始，agent 为 calculator/validator/accountant，label 为展示文案。 */
    default void onAgentStart(String agent, String label) {
    }

    /** 非最终阶段的智能体输出增量（计算记录、验证记录），用于展示多智能体过程。 */
    default void onAgentOutput(String agent, String delta) {
    }

    /** 某一智能体阶段结束。 */
    default void onAgentEnd(String agent) {
    }

    /** 模型原生思考/推理增量（仅思考型模型会触发）。 */
    default void onThinking(String delta) {
    }

    /** 阶段提示：模型思考中、达到最大轮次等。 */
    default void onPhase(String label) {
    }

    /** 较早轮次已压缩为摘要；compressed 为本次折叠轮数，kept 为保留的最近轮数。 */
    default void onContextCompressed(int compressed, int kept, int summarizedTotal) {
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

    /** 工具执行结束并带追溯快照；默认退化为只通知状态。 */
    default void onToolResult(
            String toolCallId, String toolName, String state, ChatToolTrace trace) {
        onToolResult(toolCallId, toolName, state);
    }

    /** 回答正文增量。 */
    default void onText(String delta) {
    }

    /** 本轮回答结束。 */
    default void onDone(PayrollChat.Reply reply) {
    }
}
