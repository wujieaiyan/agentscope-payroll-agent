package io.agentscope.examples.payroll.domain;

/**
 * 会话的压缩上下文：早前轮次折叠成摘要，记录已纳入摘要的轮数。
 *
 * <p>摘要故意不保留金额、税额、考勤与政策比例，避免后续轮次把过期数据当成最新结果。
 */
public record ChatContextSnapshot(String summary, int summarizedTurns) {

    public static final ChatContextSnapshot EMPTY = new ChatContextSnapshot("", 0);

    public ChatContextSnapshot {
        summary = summary == null ? "" : summary;
        summarizedTurns = Math.max(0, summarizedTurns);
    }
}
