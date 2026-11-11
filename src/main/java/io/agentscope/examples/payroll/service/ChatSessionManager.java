package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.memory.MemorySeed;
import io.agentscope.examples.payroll.memory.MemorySource;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多用户、多窗口对话管理：每个会话拥有独立的 PayrollChat 实例与消息记录。
 *
 * <p>会话归属在每次读写前校验；同一会话同时只允许一轮回答，避免上下文交错。会话运行时对象
 * 无法跨实例共享，因此缓存在进程内，并通过 {@link SessionLockService} 在 Redis 上做分布式互斥。
 */
public final class ChatSessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionManager.class);
    private static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_LIMIT = 24;
    private static final int DEFAULT_MAX_CHATS = 200;
    private static final int DEFAULT_IDLE_MINUTES = 60;

    private final H2ChatStore store;
    private final PayrollChatFactory chatFactory;
    private final VectorMemoryService memory;
    private final StaticRunRecorder runs;
    private final ShadowTeamService shadow;
    private final ActiveTeamService activeTeams;
    private final SessionLockService locks;
    private final int maxCachedChats;
    private final long cacheIdleMillis;
    private final AuditService audit;
    private final Map<Long, CachedChat> chats = new ConcurrentHashMap<>();
    private final Map<Long, StaticRunRecorder.ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<Long, String> questions = new ConcurrentHashMap<>();
    private final Map<Long, String> sessionLocks = new ConcurrentHashMap<>();
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
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                SessionLockService.disabled(),
                DEFAULT_MAX_CHATS,
                DEFAULT_IDLE_MINUTES);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowTeamService shadow,
            ActiveTeamService activeTeams,
            SessionLockService locks,
            int maxCachedChats,
            int cacheIdleMinutes) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                locks,
                maxCachedChats,
                cacheIdleMinutes,
                null);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowTeamService shadow,
            ActiveTeamService activeTeams,
            SessionLockService locks,
            int maxCachedChats,
            int cacheIdleMinutes,
            AuditService audit) {
        this.store = store;
        this.chatFactory = chatFactory;
        this.memory = memory == null ? VectorMemoryService.disabled() : memory;
        this.runs = runs == null ? StaticRunRecorder.disabled() : runs;
        this.shadow = shadow == null ? ShadowTeamService.disabled() : shadow;
        this.activeTeams = activeTeams == null ? ActiveTeamService.disabled() : activeTeams;
        this.locks = locks == null ? SessionLockService.disabled() : locks;
        this.maxCachedChats = Math.max(1, maxCachedChats);
        this.cacheIdleMillis =
                cacheIdleMinutes <= 0
                        ? Long.MAX_VALUE
                        : Duration.ofMinutes(cacheIdleMinutes).toMillis();
        this.audit = audit;
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
        Optional<String> lock = locks.acquire(sessionId);
        if (lock.isEmpty()) {
            busy.remove(sessionId);
            throw new PayrollException("该会话正在其他实例上生成回答，请稍后再试");
        }
        sessionLocks.put(sessionId, lock.get());
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
            releaseSessionLock(sessionId);
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
                            reply.traces(),
                            reply.routing(),
                            reply.proposals());
            log.info(
                    "[会话] 回答已保存：sessionId={}，messageId={}，工具追溯={}条",
                    sessionId,
                    saved.id(),
                    reply.traces() == null ? 0 : reply.traces().size());
            recordProposals(sessionId, saved.id(), reply.proposals());
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
            releaseSessionLock(sessionId);
            busy.remove(sessionId);
        }
    }

    /** 提案随助手消息落库后写审计；每条提案一条，便于按 proposalId 追溯。 */
    private void recordProposals(long sessionId, long messageId, List<ActionProposal> proposals) {
        if (audit == null || proposals == null || proposals.isEmpty()) {
            return;
        }
        for (ActionProposal proposal : proposals) {
            try {
                audit.record(
                        "chat.action.propose",
                        "chat_action_proposal",
                        proposal.proposalId(),
                        null,
                        Map.of(
                                "session_id", sessionId,
                                "message_id", messageId,
                                "tool_name", proposal.toolName(),
                                "risk_level", proposal.riskLevel(),
                                "safety_source", proposal.safetySource(),
                                "summary", proposal.summary()));
            } catch (RuntimeException e) {
                log.warn(
                        "[审计] 记录提案失败：proposalId={}，原因={}",
                        proposal.proposalId(),
                        rootMessage(e));
            }
        }
    }

    /** 按消息读取计算依据，先校验会话归属。 */
    public List<ChatToolTrace> traces(long userId, long sessionId, long messageId) {
        store.requireOwned(sessionId, userId);
        return store.traces(sessionId, messageId);
    }

    /** 单条助手消息的路由快照；会话不存在或不属于当前用户时按 404 处理。 */
    public ChatRouting routing(long userId, long sessionId, long messageId) {
        ChatSession session =
                store.find(sessionId)
                        .filter(item -> item.userId() == userId)
                        .orElseThrow(() -> new PayrollNotFoundException("会话不存在或无权访问"));
        return store.message(session.id(), messageId)
                .map(ChatMessage::routing)
                .orElseThrow(() -> new PayrollNotFoundException("消息不存在"));
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
            releaseSessionLock(sessionId);
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
        releaseSessionLock(sessionId);
        busy.remove(sessionId);
    }

    private void releaseSessionLock(long sessionId) {
        String token = sessionLocks.remove(sessionId);
        if (token != null) {
            locks.release(sessionId, token);
        }
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
        CachedChat cached = chats.get(sessionId);
        if (cached == null) {
            return List.of();
        }
        try {
            return cached.chat().stageRuns();
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
        long now = System.currentTimeMillis();
        CachedChat cached =
                chats.compute(
                        sessionId,
                        (key, existing) ->
                                existing == null
                                        ? new CachedChat(chatFactory.create(key, userId), now)
                                        : existing.touch(now));
        evictChats(now);
        return cached.chat();
    }

    /** 当前进程缓存的会话数，供监控与测试使用。 */
    public int cachedChatCount() {
        return chats.size();
    }

    /**
     * 只淘汰空闲或超额的会话，且跳过正在生成的会话；淘汰后下次访问会按数据库上下文重建。
     */
    private void evictChats(long now) {
        int excess = chats.size() - maxCachedChats;
        if (excess <= 0 && cacheIdleMillis == Long.MAX_VALUE) {
            return;
        }
        List<Map.Entry<Long, CachedChat>> snapshot = new ArrayList<>(chats.entrySet());
        snapshot.sort(Comparator.comparingLong(entry -> entry.getValue().lastAccess()));
        for (Map.Entry<Long, CachedChat> entry : snapshot) {
            boolean idle = now - entry.getValue().lastAccess() > cacheIdleMillis;
            if (!idle && excess <= 0) {
                continue;
            }
            if (busy.contains(entry.getKey())) {
                continue;
            }
            if (chats.remove(entry.getKey(), entry.getValue())) {
                closeChat(entry.getKey(), entry.getValue().chat());
                excess--;
            }
        }
    }

    private void closeChat(long sessionId) {
        CachedChat cached = chats.remove(sessionId);
        if (cached != null) {
            closeChat(sessionId, cached.chat());
        }
    }

    private void closeChat(long sessionId, PayrollChat chat) {
        try {
            chat.close();
        } catch (RuntimeException e) {
            log.warn("[会话] 关闭对话实例失败：sessionId={}，原因={}", sessionId, e.getMessage());
        }
    }

    @Override
    public void close() {
        chats.keySet().forEach(this::closeChat);
        busy.clear();
        sessionLocks.forEach(locks::release);
        sessionLocks.clear();
    }

    private static final class CachedChat {

        private final PayrollChat chat;
        private volatile long lastAccess;

        private CachedChat(PayrollChat chat, long lastAccess) {
            this.chat = chat;
            this.lastAccess = lastAccess;
        }

        private PayrollChat chat() {
            return chat;
        }

        private long lastAccess() {
            return lastAccess;
        }

        private CachedChat touch(long now) {
            lastAccess = now;
            return this;
        }
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
