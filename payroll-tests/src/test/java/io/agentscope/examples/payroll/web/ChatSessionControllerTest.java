package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.chat.ChatAttachmentService;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.kb.KbPermissionService;
import io.agentscope.examples.payroll.kb.KbSeriesStore;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatFeedbackService;
import io.agentscope.examples.payroll.service.ChatQuotaService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.service.TokenBudgetService;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatSessionControllerTest {

    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 11L;

    private final ChatSessionManager manager = mock(ChatSessionManager.class);
    private final ChatFeedbackService feedbackService = mock(ChatFeedbackService.class);
    private final TeamRunQueryService teamQueryService = mock(TeamRunQueryService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AuthService authService = mock(AuthService.class);
    private final ChatQuotaService chatQuotaService = mock(ChatQuotaService.class);
    private final TokenBudgetService tokenBudgetService =
            new TokenBudgetService(mock(LlmUsageStore.class), 0);
    private final AgentRuntimeClient runtimeClient = mock(AgentRuntimeClient.class);
    private final KbPermissionService kbPermissionService = mock(KbPermissionService.class);
    private final KbSeriesStore kbSeriesStore = mock(KbSeriesStore.class);
    private final ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        when(authService.currentUser(request))
                .thenReturn(
                        new AuthUser(
                                USER_ID,
                                "zhangwei",
                                "张伟",
                                "EMPLOYEE",
                                "员工",
                                3L,
                                "研发部",
                                Set.of()));
    }

    @Test
    @DisplayName("执行器饱和时释放配额并映射为 429")
    void executorRejectionReleasesQuota() {
        ChatSessionController controller = controller(task -> {
            throw new RejectedExecutionException("full");
        });
        when(chatQuotaService.tryAcquire(USER_ID)).thenReturn(true);

        TooManyRequestsException error =
                assertThrows(
                        TooManyRequestsException.class,
                        () -> controller.stream(SESSION_ID, message("帮我查一下上个月工资"), request));

        assertTrue(error.getMessage().contains("请求过多"));
        verify(chatQuotaService).release(USER_ID);
    }

    @Test
    @DisplayName("对话配额已满时直接拒绝，不进入执行器")
    void quotaExhaustedRejectsWithoutScheduling() {
        Executor unused = mock(Executor.class);
        ChatSessionController controller = controller(unused);
        when(chatQuotaService.tryAcquire(USER_ID)).thenReturn(false);

        assertThrows(
                TooManyRequestsException.class,
                () -> controller.stream(SESSION_ID, message("你好"), request));

        verifyNoInteractions(unused);
        verify(chatQuotaService, never()).release(anyLong());
    }

    @Test
    @DisplayName("执行器抛出非拒绝异常时同样释放配额并原样抛出")
    void unexpectedExecutorFailureReleasesQuota() {
        IllegalStateException boom = new IllegalStateException("执行器异常");
        ChatSessionController controller = controller(task -> {
            throw boom;
        });
        when(chatQuotaService.tryAcquire(USER_ID)).thenReturn(true);

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> controller.stream(SESSION_ID, message("你好"), request));

        assertSame(boom, error);
        verify(chatQuotaService).release(USER_ID);
    }

    @Test
    @DisplayName("运行期不可用时标记轮次失败并返回已完成的 SSE")
    void runtimeUnavailableMarksTurnFailed() {
        ChatSessionController controller = controller(Runnable::run);
        ChatSessionManager.TurnContext turn = turnContext();
        when(chatQuotaService.tryAcquire(USER_ID)).thenReturn(true);
        when(attachmentService.prepare(USER_ID, SESSION_ID, List.of()))
                .thenReturn(new ChatAttachmentService.PreparedAttachments(List.of(), ""));
        when(manager.beginTurn(USER_ID, SESSION_ID, "帮我查一下上个月工资")).thenReturn(turn);
        when(manager.turnEvents(USER_ID, SESSION_ID, turn.turnId(), 0L, 1)).thenReturn(List.of());
        when(runtimeClient.enabled()).thenReturn(true);
        doThrow(new PayrollUnavailableException("运行时不可用"))
                .when(runtimeClient)
                .submit(turn, "帮我查一下上个月工资", "");

        SseEmitter emitter =
                controller.stream(SESSION_ID, message("帮我查一下上个月工资"), request);

        assertNotNull(emitter);
        verify(manager).failTurn(turn, "运行时不可用");
    }

    @Test
    @DisplayName("空问题在占用配额前返回 SSE 错误")
    void blankMessageDoesNotAcquireQuota() {
        ChatSessionController controller = controller(Runnable::run);

        SseEmitter emitter = controller.stream(SESSION_ID, message("  "), request);

        assertNotNull(emitter);
        verifyNoInteractions(chatQuotaService);
    }

    private ChatSessionController controller(Executor executor) {
        return new ChatSessionController(
                manager,
                feedbackService,
                teamQueryService,
                auditService,
                authService,
                chatQuotaService,
                tokenBudgetService,
                runtimeClient,
                kbPermissionService,
                kbSeriesStore,
                attachmentService,
                3,
                executor,
                executor);
    }

    private static ChatSessionController.ChatMessageRequest message(String text) {
        return new ChatSessionController.ChatMessageRequest(text, null);
    }

    private static ChatSessionManager.TurnContext turnContext() {
        return new ChatSessionManager.TurnContext(
                "turn-1", USER_ID, SESSION_ID, 21L, 1L, "token", "node-a", null);
    }
}
