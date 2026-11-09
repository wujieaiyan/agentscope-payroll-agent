package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 用户对某条助手回复的 👍/👎 反馈，同一用户对同一消息只保留一条。 */
public record ChatFeedback(
        long id,
        long messageId,
        long sessionId,
        long userId,
        String rating,
        List<String> tags,
        String comment,
        LocalDateTime createdAt) {

    public static final String RATING_UP = "UP";
    public static final String RATING_DOWN = "DOWN";

    public ChatFeedback {
        rating = rating == null ? "" : rating;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
