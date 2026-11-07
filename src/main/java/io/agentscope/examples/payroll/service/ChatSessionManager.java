package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.memory.MemorySeed;
import io.agentscope.examples.payroll.memory.MemorySource;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多用户、多窗口对话管理：每个会话拥有独立的 PayrollChat 实例与消息记录。
 *
 * <p>会话归属在每次读写前校验；同一会话同时只允许一轮回答，避免上下文交错。
 */
public final class ChatSessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionManager.class);
    private static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_LIMIT = 24;

    private final H2ChatStore store;
    private final PayrollChatFactory chatFactory;
    private final VectorMemoryService memory;
    private final Map<Long, PayrollChat> chats = new ConcurrentHashMap<>();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    public ChatSessionManager(H2ChatStore store, PayrollChatFactory chatFactory) {
        this(store, chatFactory, VectorMemoryService.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store, PayrollChatFactory chatFactory, VectorMemoryService memory) {
        this.store = store;
        this.chatFactory = chatFactory;
        this.memory = memory == null ? VectorMemoryService.disabled() : memory;
    }

    public List<ChatSession> sessions(long userId) {
        return store.sessions(userId);
    }

    public ChatSession create(long userId, String title) {
        return store.create(userId, normalizeTitle(title, DEFAULT_TITLE));
    }

    public ChatSession rename(long userId, long sessionId, String title) {
        store.requireOwned(sessionId, userId);
        String normalized = normalizeTitle(title, null);
        store.rename(sessionId, normalized);
        return store.requireOwned(sessionId, userId);
    }

    public void delete(long userId, long sessionId) {
        store.requireOwned(sessionId, userId);
        if (busy.contains(sessionId)) {
            throw new PayrollException("会话正在生成回答，请稍后再删除");
        }
        closeChat(sessionId);
        store.delete(sessionId);
        memory.onSessionDeleted(userId, sessionId);
    }

    public List<ChatMessage> messages(long userId, long sessionId) {
        store.requireOwned(sessionId, userId);
        return store.messages(sessionId);
    }

    public boolean vectorMemoryReady() {
        return memory.ready();
    }

    /** 管理员重建向量索引：清空后按 H2 中的历史问题与摘要重灌，返回写入条数。 */
    public int rebuildVectorMemory() {
        List<MemorySeed> seeds = new ArrayList<>();
        for (ChatSession session : store.allSessions()) {
            ChatContextSnapshot snapshot = store.loadContext(session.id());
            if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
                seeds.add(
                        new MemorySeed(
                                session.userId(),
                                session.id(),
                                null,
                                snapshot.summarizedTurns(),
                                MemorySource.SUMMARY,
                                snapshot.summary()));
            }
            int turn = 0;
            for (ChatMessage message : store.messages(session.id())) {
                if (!H2ChatStore.ROLE_USER.equals(message.role())) {
                    continue;
                }
                turn++;
                seeds.add(
                        new MemorySeed(
                                session.userId(),
                                session.id(),
                                message.id(),
                                turn,
                                MemorySource.QUESTION,
                                message.content()));
            }
        }
        return memory.rebuild(seeds);
    }

    public void reset(long userId, long sessionId) {
        store.requireOwned(sessionId, userId);
        if (busy.contains(sessionId)) {
            throw new PayrollException("会话正在生成回答，请稍后再重置");
        }
        store.clear(sessionId);
        memory.onSessionReset(userId, sessionId);
        chatFor(userId, sessionId).reset();
        store.touch(sessionId);
    }

    /** 校验归属与并发状态，记录用户消息并返回该会话专属的对话实例。 */
    public PayrollChat begin(long userId, long sessionId, String message) {
        ChatSession session = store.requireOwned(sessionId, userId);
        if (!busy.add(sessionId)) {
            throw new PayrollException("该会话正在生成回答，请稍后再试");
        }
        try {
            PayrollChat chat = chatFor(userId, sessionId);
            ChatMessage saved =
                    store.append(sessionId, H2ChatStore.ROLE_USER, message, null, null);
            memory.indexQuestion(
                    userId, sessionId, saved.id(), store.userTurnCount(sessionId), message);
            if (DEFAULT_TITLE.equals(session.title())) {
                store.rename(sessionId, autoTitle(message));
            }
            return chat;
        } catch (RuntimeException e) {
            busy.remove(sessionId);
            throw e;
        }
    }

    public void complete(long userId, long sessionId, PayrollChat.Reply reply) {
        try {
            store.append(
                    sessionId,
                    H2ChatStore.ROLE_ASSISTANT,
                    reply.reply(),
                    reply.tools(),
                    reply.model());
        } catch (RuntimeException e) {
            log.error("[会话] 保存回答失败：sessionId={}", sessionId, e);
        } finally {
            busy.remove(sessionId);
        }
    }

    public void fail(long userId, long sessionId, String message) {
        try {
            store.append(
                    sessionId,
                    H2ChatStore.ROLE_ERROR,
                    message == null ? "对话失败" : message,
                    null,
                    null);
        } catch (RuntimeException e) {
            log.error("[会话] 保存错误消息失败：sessionId={}", sessionId, e);
        } finally {
            busy.remove(sessionId);
        }
    }

    public void release(long sessionId) {
        busy.remove(sessionId);
    }

    public PayrollChat chatFor(long userId, long sessionId) {
        return chats.computeIfAbsent(sessionId, key -> chatFactory.create(key, userId));
    }

    private void closeChat(long sessionId) {
        PayrollChat chat = chats.remove(sessionId);
        if (chat != null) {
            try {
                chat.close();
            } catch (RuntimeException e) {
                log.warn("[会话] 关闭对话实例失败：sessionId={}，原因={}", sessionId, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        chats.keySet().forEach(this::closeChat);
        busy.clear();
    }

    private static String autoTitle(String message) {
        String flattened = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (flattened.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return flattened.length() <= TITLE_LIMIT
                ? flattened
                : flattened.substring(0, TITLE_LIMIT) + "…";
    }

    private static String normalizeTitle(String title, String fallback) {
        String value = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            if (fallback == null) {
                throw new PayrollException("会话名称不能为空");
            }
            return fallback;
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
