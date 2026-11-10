package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.memory.MemorySeed;
import io.agentscope.examples.payroll.memory.MemorySource;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
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
    private final StaticRunRecorder runs;
    private final ShadowTeamService shadow;
    private final ActiveTeamService activeTeams;
    private final Map<Long, PayrollChat> chats = new ConcurrentHashMap<>();
    private final Map<Long, StaticRunRecorder.ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<Long, String> questions = new ConcurrentHashMap<>();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    public ChatSessionManager(H2ChatStore store, PayrollChatFactory chatFactory) {
        this(store, chatFactory, VectorMemoryService.disabled(), StaticRunRecorder.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store, PayrollChatFactory chatFactory, VectorMemoryService memory) {
        this(store, chatFactory, memory, StaticRunRecorder.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs) {
        this(store, chatFactory, memory, runs, ShadowTeamService.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowTeamService shadow) {
        this(store, chatFactory, memory, runs, shadow, ActiveTeamService.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowTeamService shadow,
            ActiveTeamService activeTeams) {
        this.store = store;
        this.chatFactory = chatFactory;
        this.memory = memory == null ? VectorMemoryService.disabled() : memory;
        this.runs = runs == null ? StaticRunRecorder.disabled() : runs;
        this.shadow = shadow == null ? ShadowTeamService.disabled() : shadow;
        this.activeTeams = activeTeams == null ? ActiveTeamService.disabled() : activeTeams;
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
            questions.put(sessionId, message);
            memory.indexQuestion(
                    userId, sessionId, saved.id(), store.userTurnCount(sessionId), message);
            if (DEFAULT_TITLE.equals(session.title())) {
                store.rename(sessionId, autoTitle(message));
            }
            startRun(chat, userId, sessionId);
            return chat;
        } catch (RuntimeException e) {
            questions.remove(sessionId);
            busy.remove(sessionId);
            throw e;
        }
    }

    /**
     * 保存助手回答及其工具追溯，返回消息 id；失败时返回 null，SSE 仍会正常结束。
     */
    public Long complete(long userId, long sessionId, PayrollChat.Reply reply) {
        StaticRunRecorder.ActiveRun run = activeRuns.remove(sessionId);
        String question = questions.remove(sessionId);
        try {
            ChatMessage saved =
                    store.appendAssistant(
                            sessionId,
                            reply.reply(),
                            reply.tools(),
                            reply.model(),
                            reply.traces());
            log.info(
                    "[会话] 回答已保存：sessionId={}，messageId={}，工具追溯={}条",
                    sessionId,
                    saved.id(),
                    reply.traces() == null ? 0 : reply.traces().size());
            PayrollChat.TeamReply team = reply.team();
            if (team != null && team.runId() > 0) {
                if (team.succeeded()) {
                    activeTeams.completeSuccess(
                            team, saved.id(), stagesOf(sessionId), tracesOf(sessionId, saved.id()));
                    cancelRun(run, sessionId, "动态团队已接管本轮回答");
                } else {
                    succeedRun(run, sessionId, saved.id());
                    activeTeams.completeFallback(
                            team, saved.id(), run == null ? 0L : run.runId());
                }
            } else {
                succeedRun(run, sessionId, saved.id());
                shadow.trigger(
                        userId,
                        sessionId,
                        saved.id(),
                        question,
                        run == null ? null : run.runId());
            }
            return saved.id();
        } catch (RuntimeException e) {
            log.error("[会话] 保存回答失败：sessionId={}", sessionId, e);
            failRun(run, sessionId, 0L, "保存回答失败：" + rootMessage(e));
            return null;
        } finally {
            busy.remove(sessionId);
        }
    }

    /** 按消息读取计算依据，先校验会话归属。 */
    public List<ChatToolTrace> traces(long userId, long sessionId, long messageId) {
        store.requireOwned(sessionId, userId);
        return store.traces(sessionId, messageId);
    }

    public void fail(long userId, long sessionId, String message) {
        long messageId = 0L;
        questions.remove(sessionId);
        try {
            ChatMessage saved =
                    store.append(
                            sessionId,
                            H2ChatStore.ROLE_ERROR,
                            message == null ? "对话失败" : message,
                            null,
                            null);
            messageId = saved.id();
        } catch (RuntimeException e) {
            log.error("[会话] 保存错误消息失败：sessionId={}", sessionId, e);
        } finally {
            failRun(activeRuns.remove(sessionId), sessionId, messageId, message);
            busy.remove(sessionId);
        }
    }

    public void release(long sessionId) {
        questions.remove(sessionId);
        StaticRunRecorder.ActiveRun run = activeRuns.remove(sessionId);
        if (run != null) {
            try {
                runs.interrupted(run, 0L, stagesOf(sessionId), List.of());
            } catch (RuntimeException e) {
                log.warn(
                        "[动态团队] 标记中断运行失败：sessionId={}，原因={}",
                        sessionId,
                        e.getMessage());
            }
        }
        busy.remove(sessionId);
    }

    /** 会话开始后创建静态运行并把坐标注入对话实例；旁路失败不影响对话。 */
    private void startRun(PayrollChat chat, long userId, long sessionId) {
        if (!runs.enabled()) {
            return;
        }
        StaticRunRecorder.ActiveRun run = null;
        try {
            run = runs.start(sessionId, userId);
            activeRuns.put(sessionId, run);
            chat.useRunScope(runs.scope(run));
        } catch (RuntimeException e) {
            activeRuns.remove(sessionId);
            if (run != null) {
                try {
                    runs.interrupted(run, 0L, List.of(), List.of());
                } catch (RuntimeException ignored) {
                    log.debug("[动态团队] 清理未启动运行失败", ignored);
                }
            }
            log.warn(
                    "[动态团队] 创建静态运行记录失败：sessionId={}，原因={}",
                    sessionId,
                    e.getMessage());
        }
    }

    private void succeedRun(StaticRunRecorder.ActiveRun run, long sessionId, long messageId) {
        if (run == null) {
            return;
        }
        try {
            runs.succeed(run, messageId, stagesOf(sessionId), tracesOf(sessionId, messageId));
        } catch (RuntimeException e) {
            log.warn("[动态团队] 保存静态运行失败：sessionId={}，原因={}", sessionId, e.getMessage());
        }
    }

    private void cancelRun(StaticRunRecorder.ActiveRun run, long sessionId, String reason) {
        if (run == null) {
            return;
        }
        try {
            runs.cancelled(run, reason);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 取消静态运行失败：sessionId={}，原因={}", sessionId, e.getMessage());
        }
    }

    private List<ChatToolTrace> tracesOf(long sessionId, long messageId) {
        try {
            return store.traces(sessionId, messageId);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 读取本次追溯失败：sessionId={}，原因={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private void failRun(
            StaticRunRecorder.ActiveRun run, long sessionId, long messageId, String reason) {
        if (run == null) {
            return;
        }
        try {
            runs.fail(run, messageId, stagesOf(sessionId), List.of(), reason);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 保存失败运行失败：sessionId={}，原因={}", sessionId, e.getMessage());
        }
    }

    private List<PayrollChat.StageRun> stagesOf(long sessionId) {
        PayrollChat chat = chats.get(sessionId);
        if (chat == null) {
            return List.of();
        }
        try {
            return chat.stageRuns();
        } catch (RuntimeException e) {
            log.warn("[动态团队] 读取阶段摘要失败：sessionId={}，原因={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
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
