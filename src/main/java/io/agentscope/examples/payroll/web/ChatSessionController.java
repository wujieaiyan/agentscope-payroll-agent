package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.Executor;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 多用户多窗口对话接口：会话列表、历史消息、流式回答与重置。 */
@RestController
@RequestMapping("/api/chat/sessions")
@RequirePermission(Permissions.CHAT_USE)
public class ChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);
    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final ChatSessionManager manager;
    private final AuthService authService;
    private final Executor chatStreamExecutor;

    public ChatSessionController(
            ChatSessionManager manager,
            AuthService authService,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.manager = manager;
        this.authService = authService;
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

    @PostMapping("/{sessionId}/messages")
    public ApiResponse send(
            @PathVariable long sessionId,
            @RequestBody(required = false) ChatMessageRequest body,
            HttpServletRequest request) {
        long userId = userId(request);
        String message = requireMessage(body == null ? null : body.message());
        PayrollChat chat = manager.begin(userId, sessionId, message);
        try {
            PayrollChat.Reply reply = chat.chat(message);
            manager.complete(userId, sessionId, reply);
            return ApiResponse.ok(reply);
        } catch (RuntimeException e) {
            manager.fail(userId, sessionId, rootMessage(e));
            throw e;
        } finally {
            manager.release(sessionId);
        }
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
        chatStreamExecutor.execute(() -> stream(userId, sessionId, message, channel, emitter));
        return emitter;
    }

    private void stream(
            long userId,
            long sessionId,
            String message,
            ChatSse.Channel channel,
            SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        boolean started = false;
        try {
            PayrollChat chat = manager.begin(userId, sessionId, message);
            started = true;
            chat.stream(
                    message,
                    ChatSse.bridge(
                            channel, reply -> manager.complete(userId, sessionId, reply)));
            emitter.complete();
            log.info(
                    "[会话] 对话完成：sessionId={}，耗时={}ms",
                    sessionId,
                    System.currentTimeMillis() - startedAt);
        } catch (PayrollException e) {
            if (started) {
                manager.fail(userId, sessionId, e.getMessage());
            }
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("[会话] 流式对话异常：sessionId={}", sessionId, e);
            if (started) {
                manager.fail(userId, sessionId, rootMessage(e));
            }
            channel.send("error", ChatSse.payload("message", "对话失败，请查看服务端日志"));
            emitter.complete();
        } finally {
            manager.release(sessionId);
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
}
