package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import java.util.List;

/** 会话上下文持久化端口：摘要按会话保存，最近轮次直接复用已落库的消息。 */
public interface ChatContextStore {

    ChatContextSnapshot loadContext(long sessionId);

    void saveContext(long sessionId, ChatContextSnapshot snapshot);

    List<ChatMessage> messages(long sessionId);

    /** 会话绑定的角色标识（流程 id 或智能体 id）；空串表示尚未绑定。 */
    default String persona(long sessionId) {
        return "";
    }

    default void savePersona(long sessionId, String persona) {
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
