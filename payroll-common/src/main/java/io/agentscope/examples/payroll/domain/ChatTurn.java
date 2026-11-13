package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/**
 * 一轮对话的执行记录：用于跨节点续流、故障接管与并发互斥。
 *
 * <p>轮次只保存坐标与状态，事件本体在 Redis Stream；最终消息仍落 chat_messages。
 */
public record ChatTurn(
        String turnId,
        long sessionId,
        long userId,
        long userMessageId,
        long assistantMessageId,
        String status,
        String ownerNode,
        long fencingToken,
        long lastSeq,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    public static final String EXPIRED = "EXPIRED";

    public static boolean terminal(String status) {
        return SUCCEEDED.equals(status)
                || FAILED.equals(status)
                || CANCELED.equals(status)
                || EXPIRED.equals(status);
    }

    public ChatTurn withLastSeq(long value) {
        return new ChatTurn(
                turnId,
                sessionId,
                userId,
                userMessageId,
                assistantMessageId,
                status,
                ownerNode,
                fencingToken,
                value,
                errorMessage,
                startedAt,
                updatedAt,
                finishedAt);
    }
}
