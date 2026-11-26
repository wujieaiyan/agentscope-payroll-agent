package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 会话上下文持久化端口：摘要按会话保存，最近轮次直接复用已落库的消息。 */
public interface ChatContextStore {

    ChatContextSnapshot loadContext(long sessionId);

    void saveContext(long sessionId, ChatContextSnapshot snapshot);

    List<ChatMessage> messages(long sessionId);

    /** 水位之后的最近 {@code limit} 条消息（升序）；按 {@code message_id DESC LIMIT} 取后反转。 */
    default List<ChatMessage> messagesRecent(long sessionId, long afterMessageId, int limit) {
        List<ChatMessage> after = new ArrayList<>();
        for (ChatMessage message : messages(sessionId)) {
            if (message.id() > afterMessageId) {
                after.add(message);
            }
        }
        return last(after, limit);
    }

    /** {@code beforeId} 之前的最近 {@code limit} 条消息（升序），用于消息列表上滑翻页。 */
    default List<ChatMessage> messagesBefore(long sessionId, long beforeId, int limit) {
        List<ChatMessage> before = new ArrayList<>();
        for (ChatMessage message : messages(sessionId)) {
            if (message.id() < beforeId) {
                before.add(message);
            }
        }
        return last(before, limit);
    }

    /** 按 id 取单条消息；不存在时为空。 */
    default Optional<ChatMessage> message(long sessionId, long messageId) {
        for (ChatMessage message : messages(sessionId)) {
            if (message.id() == messageId) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    private static List<ChatMessage> last(List<ChatMessage> messages, int limit) {
        int size = messages.size();
        return size <= limit ? List.copyOf(messages) : List.copyOf(messages.subList(size - limit, size));
    }

    /** 会话绑定的角色标识（流程 id 或智能体 id）；空串表示尚未绑定。 */
    default String persona(long sessionId) {
        return "";
    }

    default void savePersona(long sessionId, String persona) {
    }

    /** 会话显式绑定的知识库 ID；空表示未收窄，检索时默认使用当前账号可读的全部已启用库。 */
    default List<Long> kbSeriesIds(long sessionId) {
        return List.of();
    }

    /** 最近一条助手消息是否带引用；用于判断本轮是否为知识库追问。 */
    default boolean lastAssistantCited(long sessionId) {
        return false;
    }

    /** 角色切换时作废会话内未确认的写操作提案，返回作废数量。 */
    default int cancelPendingActions(long sessionId) {
        return 0;
    }

    /** CLI 等无持久化场景使用的空实现。 */
    static ChatContextStore noop() {
        return new ChatContextStore() {
            @Override
            public ChatContextSnapshot loadContext(long sessionId) {
                return ChatContextSnapshot.EMPTY;
            }

            @Override
            public void saveContext(long sessionId, ChatContextSnapshot snapshot) {
            }

            @Override
            public List<ChatMessage> messages(long sessionId) {
                return List.of();
            }
        };
    }
}
