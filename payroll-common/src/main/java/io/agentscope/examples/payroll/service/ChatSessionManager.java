package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatSession;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.memory.MemorySeed;
import io.agentscope.examples.payroll.memory.MemorySource;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.team.ActiveTeamCoordinator;
import io.agentscope.examples.payroll.team.ShadowEvaluator;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多用户、多窗口对话管理：每个会话拥有独立的 PayrollChat 实例与消息记录。
 *
 * <p>会话归属在每次读写前校验；同一会话同时只允许一轮回答，避免上下文交错。会话运行时对象
 * 无法跨实例共享，因此缓存在进程内；跨实例互斥统一由 {@link TurnLeaseService} 的轮次租约承担。
 */
public final class ChatSessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionManager.class);
    private static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_LIMIT = 24;
    private static final int DEFAULT_MAX_CHATS = 200;
    private static final int DEFAULT_IDLE_MINUTES = 60;
    private static final int DEFAULT_TURN_DEADLINE_MINUTES = 30;
    private static final int DEFAULT_PROGRESS_TIMEOUT_SECONDS = 300;
    /** 单事件负载上限：超限事件会截断文本增量，避免撑爆 Stream 与 SSE 连接。 */
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final H2ChatStore store;
    private final PayrollChatFactory chatFactory;
    private final VectorMemoryService memory;
    private final StaticRunRecorder runs;
    private final ShadowEvaluator shadow;
    private final ActiveTeamCoordinator activeTeams;
    private final int maxCachedChats;
    private final long cacheIdleMillis;
    private final AuditService audit;
    private final H2ChatTurnStore turnStore;
    private final TurnLeaseService leases;
    private final TurnEventBus events;
    private final TurnEventFanout fanout;
    private final String nodeId;
    private final Map<Long, CachedChat> chats = new ConcurrentHashMap<>();
    private final Map<Long, StaticRunRecorder.ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<Long, String> questions = new ConcurrentHashMap<>();
    private final Map<Long, String> sessionLeases = new ConcurrentHashMap<>();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final Set<String> remoteExecuting = ConcurrentHashMap.newKeySet();
    private final Map<String, Heartbeat> executing = new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance;
    private final long staleMillis;
    private final Duration turnDeadline;
    private final long progressTimeoutMillis;

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
        this(store, chatFactory, memory, runs, ShadowEvaluator.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow) {
        this(store, chatFactory, memory, runs, shadow, ActiveTeamCoordinator.disabled());
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                DEFAULT_MAX_CHATS,
                DEFAULT_IDLE_MINUTES);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams,
            int maxCachedChats,
            int cacheIdleMinutes) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                maxCachedChats,
                cacheIdleMinutes,
                null);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams,
            int maxCachedChats,
            int cacheIdleMinutes,
            AuditService audit) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                maxCachedChats,
                cacheIdleMinutes,
                audit,
                null,
                TurnLeaseService.disabled(),
                new InMemoryTurnEventBus(),
                "local");
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams,
            int maxCachedChats,
            int cacheIdleMinutes,
            AuditService audit,
            H2ChatTurnStore turnStore,
            TurnLeaseService leases,
            TurnEventBus events,
            String nodeId) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                maxCachedChats,
                cacheIdleMinutes,
                audit,
                turnStore,
                leases,
                events,
                nodeId,
                10);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams,
            int maxCachedChats,
            int cacheIdleMinutes,
            AuditService audit,
            H2ChatTurnStore turnStore,
            TurnLeaseService leases,
            TurnEventBus events,
            String nodeId,
            int heartbeatSeconds) {
        this(
                store,
                chatFactory,
                memory,
                runs,
                shadow,
                activeTeams,
                maxCachedChats,
                cacheIdleMinutes,
                audit,
                turnStore,
                leases,
                events,
                nodeId,
                heartbeatSeconds,
                DEFAULT_TURN_DEADLINE_MINUTES,
                DEFAULT_PROGRESS_TIMEOUT_SECONDS);
    }

    public ChatSessionManager(
            H2ChatStore store,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            StaticRunRecorder runs,
            ShadowEvaluator shadow,
            ActiveTeamCoordinator activeTeams,
            int maxCachedChats,
            int cacheIdleMinutes,
            AuditService audit,
            H2ChatTurnStore turnStore,
            TurnLeaseService leases,
            TurnEventBus events,
            String nodeId,
            int heartbeatSeconds,
            int turnDeadlineMinutes,
            int progressTimeoutSeconds) {
        this.store = store;
        this.chatFactory = chatFactory;
        this.memory = memory == null ? VectorMemoryService.disabled() : memory;
        this.runs = runs == null ? StaticRunRecorder.disabled() : runs;
        this.shadow = shadow == null ? ShadowEvaluator.disabled() : shadow;
        this.activeTeams = activeTeams == null ? ActiveTeamCoordinator.disabled() : activeTeams;
        this.maxCachedChats = Math.max(1, maxCachedChats);
        this.cacheIdleMillis =
                cacheIdleMinutes <= 0
                        ? Long.MAX_VALUE
                        : Duration.ofMinutes(cacheIdleMinutes).toMillis();
        this.audit = audit;
        this.turnStore = turnStore;
        this.leases = leases == null ? TurnLeaseService.disabled() : leases;
        this.events = events == null ? new InMemoryTurnEventBus() : events;
        this.fanout = new TurnEventFanout(this.events);
        this.nodeId = nodeId == null || nodeId.isBlank() ? "node" : nodeId;
        long heartbeatMillis = Math.max(1L, heartbeatSeconds) * 1000L;
        this.staleMillis = Math.max(30_000L, heartbeatMillis * 3L);
        this.turnDeadline = Duration.ofMinutes(Math.max(1, turnDeadlineMinutes));
        this.progressTimeoutMillis =
                Math.max(30_000L, Math.max(1, progressTimeoutSeconds) * 1000L);
        this.maintenance =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "turn-maintenance");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.maintenance.scheduleWithFixedDelay(
                this::heartbeatExecuting,
                heartbeatMillis,
                heartbeatMillis,
                TimeUnit.MILLISECONDS);
        this.maintenance.scheduleWithFixedDelay(
                this::scanStaleTurns, 60_000L, 60_000L, TimeUnit.MILLISECONDS);
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

    public List<Long> kbSeriesIds(long userId, long sessionId) {
        store.requireOwned(sessionId, userId);
        return store.kbSeriesIds(sessionId);
    }

    public void updateKbSeriesIds(long userId, long sessionId, List<Long> seriesIds) {
        store.requireOwned(sessionId, userId);
        store.saveKbSeriesIds(sessionId, seriesIds);
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
        if (chatFactory != null) {
            chatFor(userId, sessionId).reset();
        }
        store.touch(sessionId);
    }

    /** 校验归属与并发状态，记录用户消息并返回该会话专属的对话实例。 */
    public PayrollChat begin(long userId, long sessionId, String message) {
        ChatSession session = store.requireOwned(sessionId, userId);
        if (!busy.add(sessionId)) {
            throw new PayrollException("该会话正在生成回答，请稍后再试");
        }
        Optional<String> lease = leases.acquire(sessionId, TurnLeaseService.newTurnId(), 0L);
        if (lease.isEmpty()) {
            busy.remove(sessionId);
            throw new PayrollException("该会话正在其他实例上生成回答，请稍后再试");
        }
        leases.track(sessionId, lease.get());
        sessionLeases.put(sessionId, lease.get());
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
            releaseSessionLease(sessionId);
            busy.remove(sessionId);
            throw e;
        }
    }

    /**
     * 保存助手回答及其工具追溯，返回消息 id；失败时返回 null，SSE 仍会正常结束。
     */
    public Long complete(long userId, long sessionId, PayrollChat.Reply reply) {
        return saveReply(userId, sessionId, reply);
    }

    private Long saveReply(long userId, long sessionId, PayrollChat.Reply reply) {
        return saveReply(userId, sessionId, reply, null);
    }

    private Long saveReply(
            long userId, long sessionId, PayrollChat.Reply reply, String turnId) {
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
                            reply.proposals(),
                            turnId,
                            reply.citations());
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
            releaseSessionLease(sessionId);
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
            releaseSessionLease(sessionId);
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
        releaseSessionLease(sessionId);
        busy.remove(sessionId);
    }

    /** 一轮对话的执行坐标：租约令牌用于续约、写回与故障接管。 */
    public record TurnContext(
            String turnId,
            long userId,
            long sessionId,
            long userMessageId,
            long fencingToken,
            String token,
            String ownerNode,
            PayrollChat chat) {
    }

    /** 开启一轮对话：校验归属、抢占租约、落用户消息与轮次行。 */
    public TurnContext beginTurn(long userId, long sessionId, String message) {
        return beginTurn(userId, sessionId, message, nodeId);
    }

    public TurnContext beginTurn(long userId, long sessionId, String message, String ownerNode) {
        ChatSession session = store.requireOwned(sessionId, userId);
        if (!busy.add(sessionId)) {
            throw new PayrollException("该会话正在生成回答，请稍后再试");
        }
        String turnId = TurnLeaseService.newTurnId();
        long fencing = turnStore == null ? System.nanoTime() : turnStore.nextFencing(sessionId);
        Optional<String> lease = leases.acquire(sessionId, turnId, fencing);
        if (lease.isEmpty()) {
            busy.remove(sessionId);
            throw new PayrollException("该会话正在其他实例上生成回答，请稍后再试");
        }
        String resolvedNode = ownerNode == null || ownerNode.isBlank() ? nodeId : ownerNode;
        ChatTurn created = null;
        try {
            // Web 节点不执行模型：chatFactory 为空时只负责落轮次，由 agent 节点接管执行。
            PayrollChat chat = chatFactory == null ? null : chatFor(userId, sessionId);
            long userMessageId;
            if (turnStore != null) {
                created =
                        turnStore.createWithUserMessage(
                                turnId,
                                sessionId,
                                userId,
                                message,
                                resolvedNode,
                                fencing,
                                turnDeadline);
                userMessageId = created.userMessageId();
            } else {
                ChatMessage saved =
                        store.append(sessionId, H2ChatStore.ROLE_USER, message, null, null);
                userMessageId = saved.id();
            }
            questions.put(sessionId, message);
            try {
                memory.indexQuestion(
                        userId, sessionId, userMessageId, store.userTurnCount(sessionId), message);
            } catch (RuntimeException e) {
                log.warn("[记忆] 写入问题向量失败：sessionId={}，原因={}", sessionId, e.getMessage());
            }
            if (DEFAULT_TITLE.equals(session.title())) {
                try {
                    store.rename(sessionId, autoTitle(message));
                } catch (RuntimeException e) {
                    log.warn("[会话] 自动命名失败：sessionId={}，原因={}", sessionId, e.getMessage());
                }
            }
            if (chat != null) {
                leases.track(sessionId, lease.get());
                executing.put(
                        turnId,
                        new Heartbeat(
                                sessionId,
                                fencing,
                                lease.get(),
                                created == null ? null : created.deadlineAt()));
                startRun(chat, userId, sessionId);
            }
            return new TurnContext(
                    turnId, userId, sessionId, userMessageId, fencing, lease.get(), ownerNode, chat);
        } catch (RuntimeException e) {
            questions.remove(sessionId);
            executing.remove(turnId);
            if (created != null) {
                // 用户消息与轮次已提交：标记失败而不是留下永远 RUNNING 的孤儿。
                try {
                    turnStore.finish(turnId, fencing, ChatTurn.FAILED, 0L, "开启轮次失败");
                } catch (RuntimeException ignored) {
                    log.debug("[轮次] 补偿失败轮次异常：turnId={}", turnId, ignored);
                }
            }
            leases.release(sessionId, lease.get());
            busy.remove(sessionId);
            throw e;
        }
    }

    /**
     * 运行时节点接管 Web 已开启的轮次：租约令牌不变，不重复写用户消息。
     * 返回空表示轮次已结束、令牌不匹配或已被其它节点持有。
     */
    public Optional<TurnContext> acceptRemoteTurn(
            String turnId,
            long sessionId,
            long userId,
            String message,
            long fencingToken,
            String ownerNode) {
        store.requireOwned(sessionId, userId);
        if (turnStore == null || turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        Optional<ChatTurn> found =
                turnStore
                        .find(turnId)
                        .filter(item -> item.sessionId() == sessionId)
                        .filter(item -> item.fencingToken() == fencingToken)
                        .filter(item -> ChatTurn.RUNNING.equals(item.status()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!remoteExecuting.add(turnId)) {
            return Optional.empty();
        }
        String token = turnId + ":" + fencingToken;
        if (!leases.takeover(sessionId, token)) {
            remoteExecuting.remove(turnId);
            return Optional.empty();
        }
        try {
            if (!busy.add(sessionId)) {
                leases.release(sessionId, token);
                remoteExecuting.remove(turnId);
                return Optional.empty();
            }
            if (chatFactory == null) {
                leases.release(sessionId, token);
                remoteExecuting.remove(turnId);
                busy.remove(sessionId);
                return Optional.empty();
            }
            PayrollChat chat = chatFor(userId, sessionId);
            if (!turnStore.heartbeat(turnId, fencingToken)) {
                leases.release(sessionId, token);
                remoteExecuting.remove(turnId);
                busy.remove(sessionId);
                return Optional.empty();
            }
            questions.put(sessionId, message);
            executing.put(
                    turnId,
                    new Heartbeat(
                            sessionId, fencingToken, token, found.get().deadlineAt()));
            startRun(chat, userId, sessionId);
            return Optional.of(
                    new TurnContext(
                            turnId,
                            userId,
                            sessionId,
                            found.get().userMessageId(),
                            fencingToken,
                            token,
                            ownerNode == null || ownerNode.isBlank() ? nodeId : ownerNode,
                            chat));
        } catch (RuntimeException e) {
            leases.release(sessionId, token);
            remoteExecuting.remove(turnId);
            busy.remove(sessionId);
            throw e;
        }
    }

    /** 运行时执行结束（成功/失败/取消）后清理本地占用。 */
    public void finishRemoteTurn(String turnId) {
        remoteExecuting.remove(turnId);
        if (turnId != null && !turnId.isBlank()) {
            events.clearCancel(turnId);
        }
    }

    /** 内部取消：不校验用户归属，仅供已签名的 runtime 接口调用。 */
    public boolean cancelInternal(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return false;
        }
        if (turnStore == null) {
            events.cancel(turnId);
            return true;
        }
        Optional<ChatTurn> turn = turnStore.find(turnId);
        if (turn.isEmpty() || ChatTurn.terminal(turn.get().status())) {
            return false;
        }
        events.cancel(turnId);
        return true;
    }

    /** 落助手消息并结束轮次；返回消息 id，保存失败时返回 null。 */
    public Long completeTurn(TurnContext turn, PayrollChat.Reply reply) {
        if (turnCancelled(turn)) {
            discardTurn(turn);
            return null;
        }
        if (turnStore != null) {
            Optional<ChatTurn> current = turnStore.find(turn.turnId());
            if (current.isPresent() && ChatTurn.terminal(current.get().status())) {
                // 重复提交或接管重放：直接复用已落库的终态消息，不再写第二条。
                long existing = current.get().assistantMessageId();
                questions.remove(turn.sessionId());
                finishTurn(turn, current.get().status(), existing, current.get().errorMessage());
                return existing > 0 ? existing : null;
            }
            if (current.isPresent() && current.get().fencingToken() != turn.fencingToken()) {
                releaseTurn(turn, "轮次已被新执行端接管");
                return null;
            }
        }
        Long messageId = saveReply(turn.userId(), turn.sessionId(), reply, turn.turnId());
        finishTurn(
                turn,
                messageId == null ? ChatTurn.FAILED : ChatTurn.SUCCEEDED,
                messageId == null ? 0L : messageId,
                messageId == null ? "保存回答失败" : null);
        return messageId;
    }

    /** 用户已取消：回答不落库，清理运行记录并把轮次标记为 CANCELED。 */
    private void discardTurn(TurnContext turn) {
        questions.remove(turn.sessionId());
        cancelRun(activeRuns.remove(turn.sessionId()), turn.sessionId(), "用户已取消本轮回答");
        releaseSessionLease(turn.sessionId());
        finishTurn(turn, ChatTurn.CANCELED, 0L, "用户已取消本轮回答");
    }

    public void failTurn(TurnContext turn, String message) {
        long messageId = 0L;
        questions.remove(turn.sessionId());
        try {
            ChatMessage saved =
                    store.append(
                            turn.sessionId(),
                            H2ChatStore.ROLE_ERROR,
                            message == null ? "对话失败" : message,
                            null,
                            null);
            messageId = saved.id();
        } catch (RuntimeException e) {
            log.error("[会话] 保存错误消息失败：sessionId={}", turn.sessionId(), e);
        } finally {
            failRun(activeRuns.remove(turn.sessionId()), turn.sessionId(), messageId, message);
            finishTurn(turn, ChatTurn.FAILED, messageId, message);
        }
    }

    /** 请求取消：只写标记，事件消费端据此停止追加输出。 */
    public boolean cancelTurn(long userId, long sessionId, String turnId) {
        store.requireOwned(sessionId, userId);
        if (turnStore == null || turnId == null || turnId.isBlank()) {
            return false;
        }
        Optional<ChatTurn> turn = turnStore.find(turnId);
        if (turn.isEmpty() || turn.get().sessionId() != sessionId) {
            throw new PayrollNotFoundException("轮次不存在");
        }
        if (ChatTurn.terminal(turn.get().status())) {
            return false;
        }
        events.cancel(turnId);
        return true;
    }

    /** 会话当前进行中的轮次；页面刷新后据此找回 turnId。 */
    public ChatTurn runningTurn(long userId, long sessionId) {
        store.requireOwned(sessionId, userId);
        if (turnStore == null) {
            return null;
        }
        return turnStore.findRunning(sessionId).orElse(null);
    }

    /** 轮次执行端是否仍持有租约，用于续流时区分「模型慢」与「节点已失效」。 */
    public boolean leaseAlive(long sessionId) {
        return leases.alive(sessionId);
    }

    /** 读取轮次：用于续流前确认归属与状态。 */
    public ChatTurn turn(long userId, long sessionId, String turnId) {
        store.requireOwned(sessionId, userId);
        if (turnStore == null) {
            throw new PayrollNotFoundException("轮次不存在");
        }
        return turnStore
                .find(turnId)
                .filter(item -> item.sessionId() == sessionId)
                .orElseThrow(() -> new PayrollNotFoundException("轮次不存在"));
    }

    /** 写入一条轮次事件并返回序号；增量事件不刷新数据库游标，降低写放大。 */
    public long emit(TurnContext turn, String event, Map<String, Object> data) {
        if (!TurnEventBus.isKnownEvent(event)) {
            // 兼容性检查：新事件仍会下发，但先暴露给发布方登记，避免前后端契约悄悄漂移。
            log.warn("[轮次] 未注册的事件类型：turnId={}，event={}", turn.turnId(), event);
        }
        int[] size = new int[1];
        Map<String, Object> payload = limitEventSize(turn.turnId(), event, data, size);
        long seq = events.append(turn.turnId(), event, payload);
        TurnMetrics.eventBytes(size[0]);
        Heartbeat heartbeat = executing.get(turn.turnId());
        if (heartbeat != null) {
            heartbeat.progress(seq);
        }
        if (turnStore != null && structural(event)) {
            try {
                turnStore.updateLastSeq(turn.turnId(), turn.fencingToken(), seq);
            } catch (RuntimeException e) {
                log.debug("[轮次] 更新事件游标失败：turnId={}，原因={}", turn.turnId(), e.getMessage());
            }
        }
        return seq;
    }

    /**
     * 限制单条事件的序列化大小。常见超限来源是超长文本增量：
     * 先按 3/4 递减截断文本字段，仍超限则只保留标量字段并附截断标记，
     * 保证事件仍是合法 JSON、前端可继续拼接并最终以落库消息为准。
     */
    static Map<String, Object> limitEventSize(
            String turnId, String event, Map<String, Object> data) {
        return limitEventSize(turnId, event, data, null);
    }

    /**
     * 同 {@link #limitEventSize(String, String, Map)}，并回写最终负载字节数供指标采样，
     * 避免调用方为统计再序列化一次。
     */
    static Map<String, Object> limitEventSize(
            String turnId, String event, Map<String, Object> data, int[] sizeOut) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        int originalBytes = utf8Length(JsonSupport.toJson(data));
        if (originalBytes <= MAX_EVENT_BYTES) {
            if (sizeOut != null) {
                sizeOut[0] = originalBytes;
            }
            return data;
        }
        Map<String, Object> capped = new LinkedHashMap<>(data);
        for (String key : List.of("text", "reply", "args", "message")) {
            Object value = capped.get(key);
            if (!(value instanceof String text) || text.isEmpty()) {
                continue;
            }
            String current = text;
            while (!current.isEmpty()
                    && utf8Length(JsonSupport.toJson(capped)) > MAX_EVENT_BYTES) {
                current = current.substring(0, current.length() * 3 / 4);
                capped.put(key, current);
            }
            if (utf8Length(JsonSupport.toJson(capped)) <= MAX_EVENT_BYTES) {
                break;
            }
        }
        if (utf8Length(JsonSupport.toJson(capped)) <= MAX_EVENT_BYTES) {
            capped.put("truncated", true);
            if (sizeOut != null) {
                sizeOut[0] = utf8Length(JsonSupport.toJson(capped));
            }
            log.warn(
                    "[轮次] 事件负载超限，已截断文本字段：turnId={}，event={}，原始字节={}",
                    turnId,
                    event,
                    originalBytes);
            return capped;
        }
        Map<String, Object> minimal = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                minimal.put(entry.getKey(), value);
            } else if (value instanceof String text) {
                minimal.put(entry.getKey(), text.substring(0, Math.min(text.length(), 256)));
            }
        }
        minimal.put("truncated", true);
        minimal.put("dropped_bytes", originalBytes);
        if (sizeOut != null) {
            sizeOut[0] = utf8Length(JsonSupport.toJson(minimal));
        }
        log.warn(
                "[轮次] 事件负载超限，仅保留标量字段：turnId={}，event={}，原始字节={}",
                turnId,
                event,
                originalBytes);
        return minimal;
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public List<TurnEventBus.TurnEvent> turnEvents(
            long userId, long sessionId, String turnId, long afterSeq, int max) {
        store.requireOwned(sessionId, userId);
        return events.read(turnId, afterSeq, max);
    }

    /** 该轮次仍可读取的最早事件序号；0 表示没有可用事件。 */
    public long earliestTurnSeq(long userId, long sessionId, String turnId) {
        store.requireOwned(sessionId, userId);
        return events.earliestSeq(turnId);
    }

    /** 冷恢复快照；null 表示没有可用快照，调用方应回退到事件重放。 */
    public TurnEventBus.TurnSnapshot turnSnapshot(
            long userId, long sessionId, String turnId) {
        store.requireOwned(sessionId, userId);
        return events.snapshot(turnId);
    }

    public List<TurnEventBus.TurnEvent> waitTurnEvents(
            long userId, long sessionId, String turnId, long afterSeq, Duration block, int max) {
        store.requireOwned(sessionId, userId);
        // 同一轮次的多个订阅者共享一个节点内读取循环，Redis 读取次数不再随连接数放大。
        return fanout.await(turnId, afterSeq, block, max);
    }

    /**
     * 启动清理与周期扫描共用：先清孤儿轮次，再只终止「心跳超时且租约缺失」的轮次。
     *
     * <p>慢回答只要执行端仍在续租并写心跳就不会被误杀；执行端宕机后租约与心跳同时停止。
     */
    public int expireStaleTurns(Duration olderThan, String reason) {
        if (turnStore == null) {
            return 0;
        }
        int expired = 0;
        try {
            expired += turnStore.expireOrphanedRunning(reason);
        } catch (RuntimeException e) {
            log.warn("[轮次] 清理孤儿轮次失败：{}", e.getMessage());
        }
        try {
            // 绝对截止时间不依赖本机是否持有轮次：执行节点整体失联时由扫描统一收尾。
            expired += turnStore.expireOverdueRunning("轮次超过最长执行时间");
        } catch (RuntimeException e) {
            log.warn("[轮次] 清理超时轮次失败：{}", e.getMessage());
        }
        List<ChatTurn> stale;
        try {
            stale = turnStore.findStaleRunning(olderThan, 200);
        } catch (RuntimeException e) {
            log.warn("[轮次] 查询失联轮次失败：{}", e.getMessage());
            return expired;
        }
        for (ChatTurn turn : stale) {
            boolean alive;
            try {
                alive = leases.alive(turn.sessionId());
            } catch (RuntimeException e) {
                alive = true;
            }
            if (alive) {
                continue;
            }
            try {
                if (turnStore.finish(
                        turn.turnId(), turn.fencingToken(), ChatTurn.EXPIRED, 0L, reason)) {
                    executing.remove(turn.turnId());
                    events.clearCancel(turn.turnId());
                    TurnMetrics.leaseLost();
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("[轮次] 标记失联轮次失败：turnId={}，原因={}", turn.turnId(), e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("[轮次] 标记失联轮次 {} 条：{}", expired, reason);
        }
        return expired;
    }

    private void heartbeatExecuting() {
        if (turnStore == null || executing.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Heartbeat> entry : executing.entrySet()) {
            String turnId = entry.getKey();
            Heartbeat heartbeat = entry.getValue();
            try {
                if (!turnStore.heartbeat(turnId, heartbeat.fencingToken())) {
                    executing.remove(turnId);
                    continue;
                }
            } catch (RuntimeException e) {
                log.debug("[轮次] 心跳写入失败：turnId={}，原因={}", turnId, e.getMessage());
                continue;
            }
            String stalled = heartbeat.stallReason(now, progressTimeoutMillis);
            if (stalled != null) {
                abortStalledTurn(turnId, heartbeat, stalled);
            }
        }
    }

    /** 看门狗主动终止：截止时间已到或 last_seq 长时间不前进。 */
    private void abortStalledTurn(String turnId, Heartbeat heartbeat, String reason) {
        try {
            if (!turnStore.finish(
                    turnId, heartbeat.fencingToken(), ChatTurn.FAILED, 0L, reason)) {
                executing.remove(turnId);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("[轮次] 看门狗结束轮次失败：turnId={}，原因={}", turnId, e.getMessage());
            return;
        }
        executing.remove(turnId);
        remoteExecuting.remove(turnId);
        events.cancel(turnId);
        leases.release(heartbeat.sessionId(), heartbeat.leaseToken());
        busy.remove(heartbeat.sessionId());
        cancelRun(activeRuns.remove(heartbeat.sessionId()), heartbeat.sessionId(), reason);
        log.warn("[轮次] 看门狗终止轮次：turnId={}，原因={}", turnId, reason);
    }

    private void scanStaleTurns() {
        try {
            expireStaleTurns(Duration.ofMillis(staleMillis), "心跳超时且租约缺失");
        } catch (RuntimeException e) {
            log.warn("[轮次] 周期扫描失联轮次失败：{}", e.getMessage());
        }
    }

    /** 长时间无事件的运行中轮次标记为过期，避免前端无限等待。 */
    public boolean expireTurn(String turnId, String reason) {
        if (turnStore == null || turnId == null || turnId.isBlank()) {
            return false;
        }
        Optional<ChatTurn> turn = turnStore.find(turnId);
        if (turn.isEmpty() || !ChatTurn.RUNNING.equals(turn.get().status())) {
            return false;
        }
        boolean expired =
                turnStore.finish(
                        turnId, turn.get().fencingToken(), ChatTurn.EXPIRED, 0L, reason);
        if (expired) {
            events.clearCancel(turnId);
        }
        return expired;
    }

    private void finishTurn(
            TurnContext turn, String status, long assistantMessageId, String errorMessage) {
        executing.remove(turn.turnId());
        if (turnStore != null) {
            try {
                turnStore.finish(
                        turn.turnId(),
                        turn.fencingToken(),
                        status,
                        assistantMessageId,
                        errorMessage);
            } catch (RuntimeException e) {
                log.warn("[轮次] 结束轮次失败：turnId={}，原因={}", turn.turnId(), e.getMessage());
            }
        }
        events.clearCancel(turn.turnId());
        leases.release(turn.sessionId(), turn.token());
        busy.remove(turn.sessionId());
    }

    /**
     * 续流结束（轮次终态或客户端断开）后释放本机占用。
     *
     * <p>远程执行时轮次由 runtime 落库，Web 侧不会经过 completeTurn，
     * 必须在续流结束时清掉 busy 与本地锁记录，否则该节点会一直误判会话正在生成。
     */
    public void releaseLocalChat(long sessionId) {
        releaseSessionLease(sessionId);
        busy.remove(sessionId);
    }

    /** 未正常结束的轮次兜底释放：标记失败并归还租约与本地占用。 */
    public void releaseTurn(TurnContext turn, String reason) {
        if (turn == null) {
            return;
        }
        finishTurn(turn, ChatTurn.FAILED, 0L, reason);
    }

    private static boolean structural(String event) {
        return !"text".equals(event) && !"thinking".equals(event) && !"tool_args".equals(event);
    }

    public boolean turnCancelled(TurnContext turn) {
        return turn != null && events.cancelled(turn.turnId());
    }

    private void releaseSessionLease(long sessionId) {
        String token = sessionLeases.remove(sessionId);
        if (token != null) {
            leases.release(sessionId, token);
        }
    }

    /** 会话开始后创建静态运行并把坐标注入对话实例；旁路失败不影响对话。 */
    private void startRun(PayrollChat chat, long userId, long sessionId) {
        if (!runs.enabled() || chat == null) {
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
        if (chatFactory == null) {
            throw new PayrollException("当前节点未启用智能体运行期，无法执行模型对话");
        }
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
        maintenance.shutdownNow();
        fanout.close();
        chats.keySet().forEach(this::closeChat);
        busy.clear();
        executing.clear();
        sessionLeases.forEach(leases::release);
        sessionLeases.clear();
    }

    static final class Heartbeat {

        private final long sessionId;
        private final long fencingToken;
        private final String leaseToken;
        private final long deadlineMillis;
        private volatile long lastSeq = -1L;
        private volatile long lastProgressAt = System.currentTimeMillis();

        Heartbeat(
                long sessionId,
                long fencingToken,
                String leaseToken,
                LocalDateTime deadlineAt) {
            this.sessionId = sessionId;
            this.fencingToken = fencingToken;
            this.leaseToken = leaseToken;
            this.deadlineMillis =
                    deadlineAt == null
                            ? 0L
                            : deadlineAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }

        private long sessionId() {
            return sessionId;
        }

        private long fencingToken() {
            return fencingToken;
        }

        private String leaseToken() {
            return leaseToken;
        }

        void progress(long seq) {
            lastSeq = seq;
            lastProgressAt = System.currentTimeMillis();
        }

        /** 返回终止原因；null 表示仍在正常推进。 */
        String stallReason(long now, long progressTimeoutMillis) {
            if (deadlineMillis > 0 && now >= deadlineMillis) {
                return "轮次超过最长执行时间";
            }
            if (now - lastProgressAt >= progressTimeoutMillis) {
                return "轮次长时间无事件进展（last_seq=" + lastSeq + "）";
            }
            return null;
        }
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
