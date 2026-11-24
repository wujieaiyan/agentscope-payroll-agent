package io.agentscope.examples.payroll.domain;

/**
 * 会话的压缩上下文：早前轮次折叠成摘要，记录已纳入摘要的轮数。
 *
 * <p>摘要故意不保留金额、税额、考勤与政策比例，避免后续轮次把过期数据当成最新结果。
 *
 * <p>{@code throughMessageId} 是上下文水位：不大于该 id 的消息都已经进入摘要或按规则省略，
 * 之后的轮次直接从这里往后取，避免每次全量读会话历史。{@code summarizedTurns} 只用于展示与统计。
 */
public record ChatContextSnapshot(String summary, int summarizedTurns, long throughMessageId) {

    public static final ChatContextSnapshot EMPTY = new ChatContextSnapshot("", 0, 0);

    public ChatContextSnapshot {
        summary = summary == null ? "" : summary;
        summarizedTurns = Math.max(0, summarizedTurns);
        throughMessageId = Math.max(0, throughMessageId);
    }

    /** 未记录水位的老快照：按全量读取，由加载方回填水位。 */
    public ChatContextSnapshot(String summary, int summarizedTurns) {
        this(summary, summarizedTurns, 0);
    }

    public ChatContextSnapshot withThroughMessageId(long messageId) {
        return new ChatContextSnapshot(summary, summarizedTurns, messageId);
    }
}
