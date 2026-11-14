package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.ChatTurn;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatFeedbackService;
import io.agentscope.examples.payroll.service.ChatQuotaService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.SemanticCacheContext;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    /** 单轮续流最长等待，防止异常轮次长期占用线程。 */
    private static final long TURN_HARD_LIMIT_MS = 30 * 60_000L;

    private final ChatSessionManager manager;
    private final ChatFeedbackService feedbackService;
    private final TeamRunQueryService teamQueryService;
    private final AuditService auditService;
    private final AuthService authService;
    private final ChatQuotaService chatQuotaService;
    private final AgentRuntimeClient runtimeClient;
    private final Executor chatStreamExecutor;

    public ChatSessionController(
            ChatSessionManager manager,
            ChatFeedbackService feedbackService,
            TeamRunQueryService teamQueryService,
            AuditService auditService,
            AuthService authService,
            ChatQuotaService chatQuotaService,
            AgentRuntimeClient runtimeClient,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.manager = manager;
        this.feedbackService = feedbackService;
        this.teamQueryService = teamQueryService;
        this.auditService = auditService;
        this.authService = authService;
        this.chatQuotaService = chatQuotaService;
        this.runtimeClient = runtimeClient;
        this.chatStreamExecutor = chatStreamExecutor;
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
        manager.delete(userId(request), sessionId);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse messages(@PathVariable long sessionId, HttpServletRequest request) {
        return ApiResponse.ok(manager.messages(userId(request), sessionId));
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
        requireRuntime();
        if (!chatQuotaService.tryAcquire(userId)) {
            throw new TooManyRequestsException("当前用户的对话并发达上限，请等待上一轮回答完成");
        }
        ChatSessionManager.TurnContext turn = null;
        try {
            SemanticCacheContext.set(cacheScope(request));
            turn = manager.beginTurn(userId, sessionId, message);
            try {
                runtimeClient.submit(turn, message);
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
                null);
    }

    @PostMapping("/{sessionId}/reset")
    public ApiResponse reset(@PathVariable long sessionId, HttpServletRequest request) {
        manager.reset(userId(request), sessionId);
        return ApiResponse.ok(Map.of("reset", true));
    }

    @PostMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long sessionId,
            @RequestBody(required = false) ChatMessageRequest body,
            HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitter.complete());
        ChatSse.Channel channel = new ChatSse.Channel(emitter);
        long userId = userId(request);
        String message;
        try {
            message = requireMessage(body == null ? null : body.message());
        } catch (PayrollException e) {
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            emitter.complete();
            return emitter;
        }
        if (!chatQuotaService.tryAcquire(userId)) {
            throw new TooManyRequestsException("当前用户的对话并发达上限，请等待上一轮回答完成");
        }
        try {
            String cacheScope = cacheScope(request);
            chatStreamExecutor.execute(
                    () -> stream(userId, sessionId, message, cacheScope, channel, emitter));
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

    private void stream(
            long userId,
            long sessionId,
            String message,
            String cacheScope,
            ChatSse.Channel channel,
            SseEmitter emitter) {
        ChatSessionManager.TurnContext turn = null;
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            SemanticCacheContext.set(cacheScope);
            turn = manager.beginTurn(userId, sessionId, message);
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
                emitter.complete();
                return;
            }
            try {
                runtimeClient.submit(turn, message);
                finished.set(true);
                log.info(
                        "[运行期] 轮次已提交：sessionId={}，turnId={}",
                        sessionId,
                        turn.turnId());
                resumeEvents(userId, sessionId, turn.turnId(), 0L, channel, emitter);
                return;
            } catch (RuntimeException e) {
                if (!manager.turnEvents(userId, sessionId, turn.turnId(), 0L, 1).isEmpty()) {
                    log.warn(
                            "[运行期] 提交响应异常但事件已产生，转为续流：turnId={}，原因={}",
                            turn.turnId(),
                            e.getMessage());
                    finished.set(true);
                    resumeEvents(userId, sessionId, turn.turnId(), 0L, channel, emitter);
                    return;
                }
                log.warn(
                        "[运行期] 提交失败，不做本机兜底：turnId={}，原因={}",
                        turn.turnId(),
                        e.getMessage());
                manager.failTurn(turn, "智能体运行期不可达");
                finished.set(true);
                channel.send(
                        "error", ChatSse.payload("message", "智能体运行期不可达，请稍后再试"));
                emitter.complete();
            }
        } catch (PayrollException e) {
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.failTurn(turn, e.getMessage());
            }
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("[会话] 流式对话异常：sessionId={}", sessionId, e);
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.failTurn(turn, rootMessage(e));
            }
            channel.send("error", ChatSse.payload("message", "对话失败，请查看服务端日志"));
            emitter.complete();
        } finally {
            SemanticCacheContext.clear();
            if (turn != null && finished.compareAndSet(false, true)) {
                manager.releaseTurn(turn, "流式回答提前结束");
            }
            chatQuotaService.release(userId);
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
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitter.complete());
        ChatSse.Channel channel = new ChatSse.Channel(emitter);
        long from = Math.max(Math.max(0, afterSeq), parseEventId(lastEventId));
        chatStreamExecutor.execute(
                () -> resumeEvents(userId, sessionId, turnId, from, channel, emitter));
        return emitter;
    }

    private void resumeEvents(
            long userId,
            long sessionId,
            String turnId,
            long afterSeq,
            ChatSse.Channel channel,
            SseEmitter emitter) {
        long lastSeq = Math.max(0, afterSeq);
        long now = System.currentTimeMillis();
        long lastEventAt = now;
        long lastPingAt = now;
        long startedAt = now;
        boolean gapWarned = false;
        try {
            while (!channel.closed()) {
                List<TurnEventBus.TurnEvent> events =
                        manager.waitTurnEvents(
                                userId, sessionId, turnId, lastSeq, Duration.ofSeconds(10), 200);
                boolean done = false;
                for (TurnEventBus.TurnEvent event : events) {
                    if (!gapWarned && event.seq() > lastSeq + 1) {
                        // seq 不连续说明事件日志已被裁剪，只能重建部分内容；最终仍以 done/DB 为准。
                        log.warn(
                                "[会话] 轮次事件不连续：turnId={}，期望 seq={}，实际 seq={}",
                                turnId,
                                lastSeq + 1,
                                event.seq());
                        gapWarned = true;
                    }
                    ChatSse.replay(channel, event);
                    lastSeq = event.seq();
                    lastEventAt = System.currentTimeMillis();
                    if ("done".equals(event.event())) {
                        done = true;
                        break;
                    }
                }
                if (done || channel.closed()) {
                    break;
                }
                now = System.currentTimeMillis();
                if (!events.isEmpty()) {
                    lastPingAt = now;
                    continue;
                }
                ChatTurn turn = manager.turn(userId, sessionId, turnId);
                if (ChatTurn.terminal(turn.status())) {
                    if (!ChatTurn.SUCCEEDED.equals(turn.status())) {
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
                    if (!manager.leaseAlive(sessionId)) {
                        manager.expireTurn(turnId, "执行节点租约失效，长时间无事件");
                        channel.send(
                                "error", ChatSse.payload("message", "回答中断，请重新发起提问"));
                        break;
                    }
                    // 租约仍有效：模型或工具只是慢，继续尾随而不是误判为节点失效。
                    log.info(
                            "[会话] 轮次长时间无事件但租约有效，继续等待：turnId={}，lastSeq={}",
                            turnId,
                            lastSeq);
                    lastEventAt = now;
                }
                if (now - startedAt >= TURN_HARD_LIMIT_MS) {
                    manager.expireTurn(turnId, "轮次超过最长等待时间");
                    channel.send(
                            "error", ChatSse.payload("message", "回答超时，请重新发起提问"));
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[会话] 续流失败：sessionId={}，turnId={}", sessionId, turnId, e);
            channel.send("error", ChatSse.payload("message", "续流失败，请重新发起提问"));
        } finally {
            manager.releaseLocalChat(sessionId);
            emitter.complete();
        }
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SessionRequest(String title) {
    }

    public record ChatMessageRequest(String message) {
    }

    public record ChatFeedbackRequest(String rating, List<String> tags, String comment) {
    }
}
