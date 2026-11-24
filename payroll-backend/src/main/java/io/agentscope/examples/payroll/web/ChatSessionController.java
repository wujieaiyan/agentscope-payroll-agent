package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.chat.ChatAttachmentService;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SeriesStatus;
import io.agentscope.examples.payroll.kb.KbModels;
import io.agentscope.examples.payroll.kb.KbPermissionService;
import io.agentscope.examples.payroll.kb.KbSeriesStore;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatFeedbackService;
import io.agentscope.examples.payroll.service.ChatQuotaService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.SemanticCacheContext;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.service.TokenBudgetService;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.service.TurnMetrics;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 多用户多窗口对话接口：会话列表、历史消息、流式回答与重置。 */
@RestController
@RequestMapping("/api/chat/sessions")
@RequirePermission(Permissions.CHAT_USE)
public class ChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);
    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final long SYNC_TIMEOUT_MS = 180_000L;
    /** 续流空闲心跳间隔：让前端与反向代理知道连接仍然有效。 */
    private static final long TURN_HEARTBEAT_MS = 10_000L;
    /** 无事件超过该时长后校验租约；租约有效说明模型/工具仍在执行。 */
    private static final long TURN_LEASE_GRACE_MS = 45_000L;
    /** 单轮续流最长等待：仅在历史轮次缺少 deadline_at 时兜底。 */
    private static final long TURN_HARD_LIMIT_MS = 30 * 60_000L;

    private final ChatSessionManager manager;
    private final ChatFeedbackService feedbackService;
    private final TeamRunQueryService teamQueryService;
    private final AuditService auditService;
    private final AuthService authService;
    private final ChatQuotaService chatQuotaService;
    private final TokenBudgetService tokenBudgetService;
    private final AgentRuntimeClient runtimeClient;
    private final Executor chatStreamExecutor;
    private final Executor sseTailExecutor;
    private final KbPermissionService kbPermissionService;
    private final KbSeriesStore kbSeriesStore;
    private final ChatAttachmentService attachmentService;
    private final int maxKbSeries;

    public ChatSessionController(
            ChatSessionManager manager,
            ChatFeedbackService feedbackService,
            TeamRunQueryService teamQueryService,
            AuditService auditService,
            AuthService authService,
            ChatQuotaService chatQuotaService,
            TokenBudgetService tokenBudgetService,
            AgentRuntimeClient runtimeClient,
            KbPermissionService kbPermissionService,
            KbSeriesStore kbSeriesStore,
            ChatAttachmentService attachmentService,
            @Value("${payroll.kb.chat.max-series:3}") int maxKbSeries,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
            @Qualifier("sseTailExecutor") Executor sseTailExecutor) {
        this.manager = manager;
        this.feedbackService = feedbackService;
        this.teamQueryService = teamQueryService;
        this.auditService = auditService;
        this.authService = authService;
        this.chatQuotaService = chatQuotaService;
        this.tokenBudgetService = tokenBudgetService;
        this.runtimeClient = runtimeClient;
        this.chatStreamExecutor = chatStreamExecutor;
        this.sseTailExecutor = sseTailExecutor;
        this.kbPermissionService = kbPermissionService;
        this.kbSeriesStore = kbSeriesStore;
        this.attachmentService = attachmentService;
        this.maxKbSeries = Math.max(1, maxKbSeries);
    }

    @GetMapping
    public ApiResponse sessions(HttpServletRequest request) {
        return ApiResponse.ok(manager.sessions(userId(request)));
    }

    @PostMapping
    public ApiResponse create(
            @RequestBody(required = false) SessionRequest body, HttpServletRequest request) {
        String title = body == null ? null : body.title();
        return ApiResponse.ok(manager.create(userId(request), title));
    }

    @PutMapping("/{sessionId}")
    public ApiResponse rename(
            @PathVariable long sessionId,
            @RequestBody(required = false) SessionRequest body,
            HttpServletRequest request) {
        String title = body == null ? null : body.title();
        return ApiResponse.ok(manager.rename(userId(request), sessionId, title));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse delete(@PathVariable long sessionId, HttpServletRequest request) {
        long userId = userId(request);
        manager.delete(userId, sessionId);
        attachmentService.deleteSessionFiles(sessionId);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse messages(@PathVariable long sessionId, HttpServletRequest request) {
        return ApiResponse.ok(manager.messages(userId(request), sessionId));
    }

    /** 会话绑定的知识库：只返回有 READER 权限的库，无权绑定只回数字 ID。 */
    @GetMapping("/{sessionId}/knowledge")
    public ApiResponse knowledge(@PathVariable long sessionId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(knowledgeView(user, sessionId));
    }

    @PutMapping("/{sessionId}/knowledge")
    @RequirePermission({Permissions.CHAT_USE, Permissions.KB_READ})
    public ApiResponse bindKnowledge(
            @PathVariable long sessionId,
            @RequestBody(required = false) KnowledgeBindingRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        List<Long> requested = body == null || body.seriesIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(body.seriesIds()));
        if (requested.size() > maxKbSeries) {
            throw new PayrollException("单个会话最多绑定 " + maxKbSeries + " 个知识库");
        }
        for (Long seriesId : requested) {
            if (seriesId == null) {
                throw new PayrollException("知识库 ID 不能为空");
            }
            kbSeriesStore
                    .findById(seriesId, false)
                    .orElseThrow(() -> new PayrollException("知识库不存在：" + seriesId));
            try {
                kbPermissionService.requireRead(seriesId, user);
            } catch (PayrollForbiddenException e) {
                auditService.record(
                        "chat.kb.bind.denied",
                        "chat_session",
                        Long.toString(sessionId),
                        null,
                        Map.of("series_id", seriesId, "reason", e.getMessage()));
                throw e;
            }
        }
        List<Long> previous = manager.kbSeriesIds(user.id(), sessionId);
        manager.updateKbSeriesIds(user.id(), sessionId, requested);
        auditService.record(
                "chat.kb.bind",
                "chat_session",
                Long.toString(sessionId),
                Map.of("series_ids", previous),
                Map.of("series_ids", requested));
        return ApiResponse.ok(knowledgeView(user, sessionId));
    }

    private Map<String, Object> knowledgeView(AuthUser user, long sessionId) {
        List<Long> bound = manager.kbSeriesIds(user.id(), sessionId);
        List<Map<String, Object>> series = new ArrayList<>();
        List<Long> denied = new ArrayList<>();
        for (Long seriesId : bound) {
            KbModels.Series found = kbSeriesStore.findById(seriesId, false).orElse(null);
            KbRole role = kbPermissionService.roleIn(seriesId, user).orElse(null);
            if (found == null || role == null) {
                denied.add(seriesId);
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", found.id());
            item.put("name", found.name());
            item.put("status", found.status().name());
            item.put("role", role.name());
            item.put("searchable", found.status() == SeriesStatus.ACTIVE);
            series.add(item);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("session_id", sessionId);
        // 未显式绑定时默认检索当前账号可读的全部已启用库。
        view.put("mode", bound.isEmpty() ? "ALL" : "SELECTED");
        view.put("series", series);
        view.put("denied", denied);
        view.put("max_series", maxKbSeries);
        return view;
    }

    /** 单条助手消息的计算依据：工具名、参数、结果、金额与关联员工。 */
    @GetMapping("/{sessionId}/messages/{messageId}/traces")
    public ApiResponse traces(
            @PathVariable long sessionId,
            @PathVariable long messageId,
            HttpServletRequest request) {
        return ApiResponse.ok(manager.traces(userId(request), sessionId, messageId));
    }

    /** 单条助手消息的路由快照：路径、回退来源与参与智能体。 */
    @GetMapping("/{sessionId}/messages/{messageId}/routing")
    public ApiResponse routing(
            @PathVariable long sessionId,
            @PathVariable long messageId,
            HttpServletRequest request) {
        return ApiResponse.ok(manager.routing(userId(request), sessionId, messageId));
    }

    /** 单条助手消息的团队运行、节点摘要、追溯引用与当前反馈。 */
    @GetMapping("/{sessionId}/messages/{messageId}/team")
    public ApiResponse team(
            @PathVariable long sessionId,
            @PathVariable long messageId,
            HttpServletRequest request) {
        return ApiResponse.ok(teamQueryService.view(userId(request), sessionId, messageId));
    }

    @GetMapping("/{sessionId}/messages/{messageId}/feedback")
    public ApiResponse feedback(
            @PathVariable long sessionId,
            @PathVariable long messageId,
            HttpServletRequest request) {
        return ApiResponse.ok(
                feedbackService.find(userId(request), sessionId, messageId).orElse(null));
    }

    @PostMapping("/{sessionId}/messages/{messageId}/feedback")
    public ApiResponse submitFeedback(
            @PathVariable long sessionId,
            @PathVariable long messageId,
            @RequestBody(required = false) ChatFeedbackRequest body,
            HttpServletRequest request) {
        ChatFeedbackRequest values =
                body == null ? new ChatFeedbackRequest(null, null, null) : body;
        ChatFeedback saved =
                feedbackService.submit(
                        userId(request),
                        sessionId,
                        messageId,
                        values.rating(),
                        values.tags(),
                        values.comment());
        auditService.record(
                "chat.feedback.recorded",
                "chat_message",
                Long.toString(messageId),
                null,
                saved);
        return ApiResponse.ok(saved);
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse send(
            @PathVariable long sessionId,
            @RequestBody(required = false) ChatMessageRequest body,
            HttpServletRequest request) {
        long userId = userId(request);
        String message = requireMessage(body == null ? null : body.message());
        List<Long> attachmentIds = attachmentIds(body);
        tokenBudgetService.check(userId);
        requireRuntime();
        if (!chatQuotaService.tryAcquire(userId)) {
            throw new TooManyRequestsException("当前用户的对话并发达上限，请等待上一轮回答完成");
        }
        ChatSessionManager.TurnContext turn = null;
        try {
            SemanticCacheContext.set(cacheScope(request));
            ChatAttachmentService.PreparedAttachments attachments =
                    attachmentService.prepare(userId, sessionId, attachmentIds);
            turn = manager.beginTurn(userId, sessionId, message);
            attachmentService.bind(userId, sessionId, attachments.ids(), turn.userMessageId());
            try {
                runtimeClient.submit(turn, message, attachments.text());
            } catch (RuntimeException e) {
                if (manager.turnEvents(userId, sessionId, turn.turnId(), 0L, 1).isEmpty()) {
                    manager.failTurn(turn, rootMessage(e));
                    throw new PayrollException("智能体运行期不可达，请稍后再试");
                }
                log.warn(
                        "[运行期] 提交响应异常但事件已产生，转为等待终态：turnId={}，原因={}",
                        turn.turnId(),
                        e.getMessage());
            }
            ChatTurn finished = awaitTurn(userId, sessionId, turn.turnId());
            if (!ChatTurn.SUCCEEDED.equals(finished.status())) {
                throw new PayrollException(
                        finished.errorMessage() == null
                                ? "回答中断，请重试"
                                : finished.errorMessage());
            }
            return ApiResponse.ok(replyOf(userId, sessionId, finished));
        } finally {
            SemanticCacheContext.clear();
            if (turn != null) {
                manager.releaseLocalChat(sessionId);
            }
            chatQuotaService.release(userId);
        }
    }

    /** 后端不执行模型：未配置运行期时直接给出明确错误，避免静默降级。 */
    private void requireRuntime() {
        if (!runtimeClient.enabled()) {
            throw new PayrollException("智能体运行期未配置，暂时无法回答问题");
        }
    }

    /** 同步接口等待 runtime 落库终态；超时视为节点失效并标记轮次失败。 */
    private ChatTurn awaitTurn(long userId, long sessionId, String turnId) {
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ChatTurn turn = manager.turn(userId, sessionId, turnId);
            if (ChatTurn.terminal(turn.status())) {
                return turn;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        manager.expireTurn(turnId, "等待智能体回答超时");
        throw new PayrollException("回答超时，请稍后在会话中继续追问");
    }

    /** 从已落库的助手消息还原同步响应，保持与流式接口一致的数据结构。 */
    private PayrollChat.Reply replyOf(long userId, long sessionId, ChatTurn turn) {
        long messageId = turn.assistantMessageId();
        ChatMessage message =
                manager.messages(userId, sessionId).stream()
                        .filter(item -> item.id() == messageId)
                        .findFirst()
                        .orElseThrow(() -> new PayrollException("回答已结束，但未找到助手消息"));
        return new PayrollChat.Reply(
                message.content(),
                message.tools(),
                message.model(),
                manager.traces(userId, sessionId, messageId),
                null,
                message.routing(),
                message.proposals(),
                null,
                message.citations());
    }

    @PostMapping("/{sessionId}/reset")
    public ApiResponse reset(@PathVariable long sessionId, HttpServletRequest request) {
        long userId = userId(request);
        manager.reset(userId, sessionId);
        attachmentService.deleteSessionFiles(sessionId);
        return ApiResponse.ok(Map.of("reset", true));
    }

    @PostMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long sessionId,
            @RequestBody(required = false) ChatMessageRequest body,
            HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatSse.Channel channel = ChatSse.attach(emitter);
        long userId = userId(request);
        String message;
        List<Long> attachmentIds;
        try {
            message = requireMessage(body == null ? null : body.message());
            attachmentIds = attachmentIds(body);
        } catch (PayrollException e) {
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            channel.complete();
            return emitter;
        }
        tokenBudgetService.check(userId);
        if (!chatQuotaService.tryAcquire(userId)) {
            throw new TooManyRequestsException("当前用户的对话并发达上限，请等待上一轮回答完成");
        }
        try {
            String cacheScope = cacheScope(request);
            chatStreamExecutor.execute(
                    () ->
                            startTurn(
                                    userId,
                                    sessionId,
                                    message,
                                    attachmentIds,
                                    cacheScope,
                                    channel));
        } catch (RejectedExecutionException e) {
            chatQuotaService.release(userId);
            ChatStreamRejection.record(chatStreamExecutor, "executor");
            throw new TooManyRequestsException("当前对话请求过多，请稍后重试");
        } catch (RuntimeException e) {
            chatQuotaService.release(userId);
            throw e;
        }
        return emitter;
    }

    /** 语义缓存作用域：部门 + 权限指纹，线程内传给执行线程。 */
    private String cacheScope(HttpServletRequest request) {
        try {
            var user = authService.currentUser(request);
            return SemanticCacheContext.fingerprint(user.departmentId(), user.permissions());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 执行线程只负责建轮次与提交运行期；此后阻塞的续流交给独立的尾随线程池，
     * 避免长时间占用对话执行线程。
     */
    private void startTurn(
            long userId,
            long sessionId,
            String message,
            List<Long> attachmentIds,
            String cacheScope,
            ChatSse.Channel channel) {
        ChatSessionManager.TurnContext turn = null;
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean quotaReleased = new AtomicBoolean(false);
        try {
            SemanticCacheContext.set(cacheScope);
            ChatAttachmentService.PreparedAttachments attachments =
                    attachmentService.prepare(userId, sessionId, attachmentIds);
            turn = manager.beginTurn(userId, sessionId, message);
            attachmentService.bind(userId, sessionId, attachments.ids(), turn.userMessageId());
            channel.send(
                    "turn",
                    ChatSse.payload(
                            "turn_id", turn.turnId(),
                            "user_message_id", turn.userMessageId(),
                            "owner_node", turn.ownerNode()));
            if (!runtimeClient.enabled()) {
                manager.failTurn(turn, "智能体运行期未配置");
                finished.set(true);
                channel.send(
                        "error",
                        ChatSse.payload("message", "智能体运行期未配置，暂时无法回答问题"));
                channel.complete();
                chatQuotaService.release(userId);
                quotaReleased.set(true);
                return;
            }
            try {
                runtimeClient.submit(turn, message, attachments.text());
                finished.set(true);
                log.info(
                        "[运行期] 轮次已提交：sessionId={}，turnId={}",
                        sessionId,
                        turn.turnId());
            } catch (RuntimeException e) {
                if (!manager.turnEvents(userId, sessionId, turn.turnId(), 0L, 1).isEmpty()) {
                    log.warn(
                            "[运行期] 提交响应异常但事件已产生，转为续流：turnId={}，原因={}",
                            turn.turnId(),
                            e.getMessage());
                    finished.set(true);
                } else {
                    log.warn(
                            "[运行期] 提交失败，不做本机兜底：turnId={}，原因={}",
                            turn.turnId(),
                            e.getMessage());
                    String failure;
                    String errorText;
                    if (e instanceof TooManyRequestsException) {
                        failure = "运行时繁忙";
                        errorText = "运行时繁忙，请稍后重试";
                    } else if (e instanceof PayrollUnavailableException) {
                        failure = "运行时不可用";
                        errorText = "运行时暂不可用，请稍后再试";
                    } else {
                        failure = "智能体运行期不可达";
                        errorText = "智能体运行期不可达，请稍后再试";
                    }
                    manager.failTurn(turn, failure);
                    finished.set(true);
                    channel.send("error", ChatSse.payload("message", errorText));
                    channel.complete();
                    chatQuotaService.release(userId);
                    quotaReleased.set(true);
                    return;
                }
            }
            // 轮次已交给运行期，尾随被拒也不能回滚轮次状态；客户端可稍后用续流接口恢复。
            if (!handoffTail(userId, sessionId, turn.turnId(), 0L, channel, true)) {
                channel.send(
                        "error", ChatSse.payload("message", "当前订阅过多，请稍后重试"));
                channel.complete();
                chatQuotaService.release(userId);
                quotaReleased.set(true);
            }
        } catch (PayrollException e) {
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.failTurn(turn, e.getMessage());
            }
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            channel.complete();
            chatQuotaService.release(userId);
            quotaReleased.set(true);
        } catch (RuntimeException e) {
            log.error("[会话] 流式对话异常：sessionId={}", sessionId, e);
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.failTurn(turn, rootMessage(e));
            }
            channel.send("error", ChatSse.payload("message", "对话失败，请查看服务端日志"));
            channel.complete();
            chatQuotaService.release(userId);
            quotaReleased.set(true);
        } finally {
            SemanticCacheContext.clear();
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.releaseTurn(turn, "流式回答提前结束");
            }
            if (!quotaReleased.get()) {
                chatQuotaService.release(userId);
            }
        }
    }

    /**
     * 把阻塞的续流交给尾随线程池。
     *
     * @return false 表示尾随队列已满；调用方负责结束本次订阅。
     */
    private boolean handoffTail(
            long userId,
            long sessionId,
            String turnId,
            long afterSeq,
            ChatSse.Channel channel,
            boolean releaseQuota) {
        try {
            sseTailExecutor.execute(
                    () -> {
                        try {
                            resumeEvents(userId, sessionId, turnId, afterSeq, channel);
                        } finally {
                            if (releaseQuota) {
                                chatQuotaService.release(userId);
                            }
                        }
                    });
            return true;
        } catch (RejectedExecutionException e) {
            ChatStreamRejection.record(sseTailExecutor, "tail");
            return false;
        }
    }

    /** 会话当前进行中的轮次：页面刷新后先查这里，再决定是否续流。 */
    @GetMapping("/{sessionId}/turns/active")
    public ApiResponse activeTurn(@PathVariable long sessionId, HttpServletRequest request) {
        ChatTurn turn = manager.runningTurn(userId(request), sessionId);
        if (turn == null) {
            return ApiResponse.ok(null);
        }
        // 字段允许为空，不能用 Map.of
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("turn_id", turn.turnId());
        view.put("status", turn.status());
        view.put("last_seq", turn.lastSeq());
        view.put("owner_node", turn.ownerNode());
        view.put("started_at", turn.startedAt());
        return ApiResponse.ok(view);
    }

    /** 轮次状态：前端断线后先查状态，再决定续流还是直接展示失败。 */
    @GetMapping("/{sessionId}/turns/{turnId}")
    public ApiResponse turnStatus(
            @PathVariable long sessionId,
            @PathVariable String turnId,
            HttpServletRequest request) {
        return ApiResponse.ok(manager.turn(userId(request), sessionId, turnId));
    }

    @PostMapping("/{sessionId}/turns/{turnId}/cancel")
    public ApiResponse cancelTurn(
            @PathVariable long sessionId,
            @PathVariable String turnId,
            HttpServletRequest request) {
        return ApiResponse.ok(
                Map.of("cancelled", manager.cancelTurn(userId(request), sessionId, turnId)));
    }

    /** 续流：按 after_seq 重放事件，轮次未结束时尾随新事件，直到 done 或终态。 */
    @GetMapping(
            value = "/{sessionId}/turns/{turnId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @PathVariable long sessionId,
            @PathVariable String turnId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request) {
        long userId = userId(request);
        manager.turn(userId, sessionId, turnId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatSse.Channel channel = ChatSse.attach(emitter);
        long from = Math.max(Math.max(0, afterSeq), parseEventId(lastEventId));
        if (!handoffTail(userId, sessionId, turnId, from, channel, false)) {
            throw new TooManyRequestsException("当前订阅过多，请稍后重试");
        }
        return emitter;
    }

    private void resumeEvents(
            long userId,
            long sessionId,
            String turnId,
            long afterSeq,
            ChatSse.Channel channel) {
        long lastSeq = Math.max(0, afterSeq);
        long now = System.currentTimeMillis();
        long lastEventAt = now;
        long lastPingAt = now;
        boolean gapWarned = false;
        long replayStart = now;
        long replayed = 0L;
        boolean replayTimed = false;
        try {
            // 截止时间在开启轮次时固定，客户端重连不会重置。
            ChatTurn initial = manager.turn(userId, sessionId, turnId);
            long deadlineMillis =
                    initial.deadlineAt() == null
                            ? now + TURN_HARD_LIMIT_MS
                            : initial.deadlineAt()
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli();
            long earliest = manager.earliestTurnSeq(userId, sessionId, turnId);
            if (earliest > 0 && lastSeq + 1 < earliest) {
                // 游标之前的事件已被裁剪，逐帧重放必然缺失，先用合并快照补足可见进度。
                // 事件仍在保留窗口内时必须按序重放：快照只保留展示状态，会吞掉
                // tool_result、action_proposal 与 done，而前端要靠它们渲染工具与提案卡片。
                TurnEventBus.TurnSnapshot snapshot =
                        manager.turnSnapshot(userId, sessionId, turnId);
                if (snapshot != null && snapshot.seq() > lastSeq) {
                    channel.send(
                            "snapshot",
                            ChatSse.payload(
                                    "seq", snapshot.seq(),
                                    "text", snapshot.text(),
                                    "tools",
                                            snapshot.tools().isEmpty() ? null : snapshot.tools(),
                                    "agents",
                                            snapshot.agents().isEmpty() ? null : snapshot.agents(),
                                    "phase", snapshot.phase(),
                                    "citations",
                                            snapshot.citations().isEmpty()
                                                    ? null
                                                    : snapshot.citations()));
                    lastSeq = snapshot.seq();
                    replayed++;
                }
                if (lastSeq + 1 < earliest) {
                    // 快照没能覆盖到保留窗口：明示缺口，前端停止拼接、以落库消息收尾。
                    sendGap(channel, lastSeq, earliest);
                    lastSeq = earliest - 1;
                    gapWarned = true;
                }
            }
            while (!channel.closed()) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    manager.expireTurn(turnId, "轮次超过最长执行时间");
                    TurnMetrics.turnExpired("deadline");
                    channel.send(
                            "error", ChatSse.payload("message", "回答超时，请重新发起提问"));
                    break;
                }
                List<TurnEventBus.TurnEvent> events =
                        manager.waitTurnEvents(
                                userId, sessionId, turnId, lastSeq, Duration.ofSeconds(10), 200);
                boolean done = false;
                StringBuilder mergedText = null;
                long mergedSeq = 0L;
                for (TurnEventBus.TurnEvent event : events) {
                    if (!gapWarned && event.seq() > lastSeq + 1) {
                        // seq 不连续说明事件日志已被裁剪，只能重建部分内容；最终仍以 done/DB 为准。
                        long available = manager.earliestTurnSeq(userId, sessionId, turnId);
                        sendGap(
                                channel,
                                lastSeq,
                                available > 0 ? Math.max(available, event.seq()) : event.seq());
                        gapWarned = true;
                    }
                    if ("text".equals(event.event())) {
                        // 连续文本合并成一帧，1 万事件轮次的 SSE 帧数可显著下降。
                        if (mergedText == null) {
                            mergedText = new StringBuilder();
                        }
                        mergedText.append(textOf(event.data()));
                        mergedSeq = event.seq();
                        lastSeq = event.seq();
                        lastEventAt = System.currentTimeMillis();
                        replayed++;
                        continue;
                    }
                    if (mergedText != null) {
                        ChatSse.replayText(channel, mergedSeq, mergedText.toString());
                        mergedText = null;
                    }
                    ChatSse.replay(channel, event);
                    lastSeq = event.seq();
                    lastEventAt = System.currentTimeMillis();
                    replayed++;
                    if ("done".equals(event.event())) {
                        done = true;
                        break;
                    }
                }
                if (mergedText != null) {
                    ChatSse.replayText(channel, mergedSeq, mergedText.toString());
                }
                if (done || channel.closed()) {
                    break;
                }
                now = System.currentTimeMillis();
                if (!events.isEmpty()) {
                    lastPingAt = now;
                    continue;
                }
                if (!replayTimed) {
                    // 第一次等到空批次即表示存量事件已追平，记录续流追平耗时。
                    replayTimed = true;
                    TurnMetrics.resumeReplay(replayStart);
                }
                ChatTurn turn = manager.turn(userId, sessionId, turnId);
                if (ChatTurn.terminal(turn.status())) {
                    if (ChatTurn.SUCCEEDED.equals(turn.status())) {
                        // done 事件可能因 Redis 抖动丢失：终态已落库时用助手消息补齐，避免前端空白。
                        replayFinalMessage(userId, sessionId, turn, channel);
                    } else {
                        channel.send(
                                "error",
                                ChatSse.payload(
                                        "message",
                                        turn.errorMessage() == null
                                                ? "回答中断，请重试"
                                                : turn.errorMessage()));
                    }
                    break;
                }
                if (now - lastPingAt >= TURN_HEARTBEAT_MS) {
                    ChatSse.heartbeat(channel, lastSeq);
                    lastPingAt = now;
                }
                if (now - lastEventAt >= TURN_LEASE_GRACE_MS) {
                    ChatTurn current = manager.turn(userId, sessionId, turnId);
                    if (ChatTurn.terminal(current.status())) {
                        if (ChatTurn.SUCCEEDED.equals(current.status())) {
                            replayFinalMessage(userId, sessionId, current, channel);
                        } else {
                            channel.send(
                                    "error",
                                    ChatSse.payload(
                                            "message",
                                            current.errorMessage() == null
                                                    ? "回答中断，请重试"
                                                    : current.errorMessage()));
                        }
                        break;
                    }
                    // 判活契约：先看 runtime 每 10s 写入的 DB 心跳，心跳新鲜就不是节点失效。
                    if (manager.heartbeatFresh(current)) {
                        log.info(
                                "[会话] 轮次长时间无事件但 DB 心跳新鲜，继续等待：turnId={}，lastSeq={}",
                                turnId,
                                lastSeq);
                        lastEventAt = now;
                        continue;
                    }
                    if (manager.leaseAlive(sessionId)) {
                        // 心跳写库失败但租约仍在续约：模型或工具只是慢，继续尾随。
                        log.warn(
                                "[会话] 轮次心跳过期但租约仍有效，继续等待：turnId={}，lastSeq={}",
                                turnId,
                                lastSeq);
                        lastEventAt = now;
                        continue;
                    }
                    TurnMetrics.leaseLost();
                    TurnMetrics.turnExpired("no-events");
                    manager.expireTurn(turnId, "执行节点心跳与租约均失效，长时间无事件");
                    channel.send(
                            "error", ChatSse.payload("message", "回答中断，请重新发起提问"));
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[会话] 续流失败：sessionId={}，turnId={}", sessionId, turnId, e);
            channel.send("error", ChatSse.payload("message", "续流失败，请重新发起提问"));
        } finally {
            if (!replayTimed) {
                // 未追平即结束（done、终态或异常）：按整段耗时采样，避免续流指标缺失。
                TurnMetrics.resumeReplay(replayStart);
            }
            TurnMetrics.replayedEvents(replayed);
            manager.releaseLocalChat(sessionId);
            channel.complete();
        }
    }

    /**
     * 终态已落库但 done 事件缺失（Redis 抖动或事件裁剪）：用助手消息补发 done。
     *
     * <p>带 {@code recovered=true}，前端在断点过期清空拼接后仍展示这段最终回答。
     */
    private void replayFinalMessage(
            long userId, long sessionId, ChatTurn turn, ChatSse.Channel channel) {
        long messageId = turn.assistantMessageId();
        if (messageId <= 0) {
            return;
        }
        ChatMessage message =
                manager.messages(userId, sessionId).stream()
                        .filter(item -> item.id() == messageId)
                        .findFirst()
                        .orElse(null);
        if (message == null) {
            log.warn(
                    "[会话] 终态已落库但助手消息缺失：turnId={}，messageId={}",
                    turn.turnId(),
                    messageId);
            return;
        }
        channel.send(
                "done",
                ChatSse.payload(
                        "reply", message.content(),
                        "tools", message.tools().isEmpty() ? null : message.tools(),
                        "model", message.model(),
                        "message_id", message.id(),
                        "routing", message.routing(),
                        "proposals", message.proposals().isEmpty() ? null : message.proposals(),
                        "citations", message.citations().isEmpty() ? null : message.citations(),
                        "recovered", true));
        log.info(
                "[会话] 终态已落库，补发最终回答：turnId={}，messageId={}",
                turn.turnId(),
                message.id());
    }

    /** 断点已过期时下发一次性缺口提示；不带 SSE id，避免污染续传游标。 */
    private static void sendGap(ChatSse.Channel channel, long afterSeq, long earliestSeq) {
        TurnMetrics.gap();
        log.warn(
                "[会话] 续流断点已过期：lastSeq={}，earliestSeq={}",
                afterSeq,
                earliestSeq);
        channel.send(
                "gap",
                ChatSse.payload(
                        "after_seq", Math.max(0L, afterSeq),
                        "earliest_seq", Math.max(0L, earliestSeq),
                        "message", "部分回答过程已过期，回答已保存，完成后可刷新查看完整内容"));
    }

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 取出文本事件里的增量内容；脏数据直接忽略，避免重放整轮失败。 */
    private static String textOf(String data) {
        if (data == null || data.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload =
                    JsonSupport.fromJson(data, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object text = payload.get("text");
            return text == null ? "" : text.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private long userId(HttpServletRequest request) {
        return authService.currentUser(request).id();
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new PayrollException("请输入要咨询的问题");
        }
        String value = message.trim();
        if (value.length() > 2000) {
            throw new PayrollException("问题过长，请控制在 2000 字以内");
        }
        return value;
    }

    /** 附件 ID 允许为空；只接受正数，具体归属由附件服务复核。 */
    private static List<Long> attachmentIds(ChatMessageRequest body) {
        if (body == null || body.attachmentIds() == null || body.attachmentIds().isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : body.attachmentIds()) {
            if (id == null || id <= 0) {
                throw new PayrollException("附件 ID 不合法");
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SessionRequest(String title) {
    }

    public record ChatMessageRequest(
            String message,
            @JsonProperty("attachment_ids") List<Long> attachmentIds) {
    }

    public record ChatFeedbackRequest(String rating, List<String> tags, String comment) {
    }

    public record KnowledgeBindingRequest(
            @JsonProperty("series_ids") List<Long> seriesIds) {
    }
}
