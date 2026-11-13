package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 助手消息反馈：校验会话归属、消息角色与标签，同一用户重复提交覆盖原记录。 */
public final class ChatFeedbackService {

    static final int COMMENT_LIMIT = 500;
    static final int TAG_LIMIT = 5;
    static final List<String> TAGS =
            List.of("WRONG_AMOUNT", "INCOMPLETE", "SLOW", "OFF_TOPIC", "OTHER");

    private final H2ChatStore chatStore;
    private final H2ChatFeedbackStore feedbackStore;

    public ChatFeedbackService(H2ChatStore chatStore, H2ChatFeedbackStore feedbackStore) {
        this.chatStore = chatStore;
        this.feedbackStore = feedbackStore;
    }

    public ChatFeedback submit(
            long userId,
            long sessionId,
            long messageId,
            String rating,
            List<String> tags,
            String comment) {
        requireAssistantMessage(userId, sessionId, messageId);
        String normalizedRating = normalizeRating(rating);
        return feedbackStore.upsert(
                messageId,
                sessionId,
                userId,
                normalizedRating,
                normalizeTags(normalizedRating, tags),
                normalizeComment(comment));
    }

    public Optional<ChatFeedback> find(long userId, long sessionId, long messageId) {
        requireAssistantMessage(userId, sessionId, messageId);
        return feedbackStore.find(messageId, userId);
    }

    private void requireAssistantMessage(long userId, long sessionId, long messageId) {
        ChatSession session =
                chatStore
                        .find(sessionId)
                        .orElseThrow(() -> new PayrollException("会话不存在或无权访问"));
        if (session.userId() != userId) {
            throw new PayrollForbiddenException("无权访问该会话");
        }
        ChatMessage message =
                chatStore
                        .message(sessionId, messageId)
                        .orElseThrow(() -> new PayrollException("消息不存在或不属于该会话"));
        if (!H2ChatStore.ROLE_ASSISTANT.equals(message.role())) {
            throw new PayrollException("仅可对助手回答反馈");
        }
    }

    private static String normalizeRating(String rating) {
        String value = rating == null ? "" : rating.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new PayrollException("请选择赞或踩");
        }
        if (!ChatFeedback.RATING_UP.equals(value) && !ChatFeedback.RATING_DOWN.equals(value)) {
            throw new PayrollException("反馈类型不合法");
        }
        return value;
    }

    private static List<String> normalizeTags(String rating, List<String> tags) {
        if (!ChatFeedback.RATING_DOWN.equals(rating) || tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = tag == null ? "" : tag.trim().toUpperCase(Locale.ROOT);
            if (!TAGS.contains(value)) {
                throw new PayrollException("反馈标签不合法：" + value);
            }
            normalized.add(value);
        }
        if (normalized.size() > TAG_LIMIT) {
            throw new PayrollException("反馈标签最多选择 " + TAG_LIMIT + " 个");
        }
        return List.copyOf(normalized);
    }

    private static String normalizeComment(String comment) {
        String value = comment == null ? "" : comment.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > COMMENT_LIMIT) {
            throw new PayrollException("反馈说明不能超过 " + COMMENT_LIMIT + " 字");
        }
        return value;
    }
}
