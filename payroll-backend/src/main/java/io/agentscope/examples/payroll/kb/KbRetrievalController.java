package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库检索测试接口。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/retrieval")
@RequirePermission(Permissions.KB_READ)
public class KbRetrievalController {

    private final KbRetrievalService retrievalService;
    private final AuthService authService;
    private final ExecutorService kbSearchExecutor;

    public KbRetrievalController(
            KbRetrievalService retrievalService,
            AuthService authService,
            @Qualifier("kbSearchExecutor") ExecutorService kbSearchExecutor) {
        this.retrievalService = retrievalService;
        this.authService = authService;
        this.kbSearchExecutor = kbSearchExecutor;
    }

    /**
     * 管理台显式检索：与聊天/团队链路共用 {@code kbSearchExecutor}，但拒绝语义不同——
     * 池满时此处回 429，聊天链路在 {@code KbSkillService} 降级为空结果。
     */
    @PostMapping("/test")
    public ApiResponse test(
            @PathVariable long seriesId,
            @RequestBody RetrievalTestRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        Future<KbDtos.RetrievalResult> future;
        try {
            future = kbSearchExecutor.submit(() -> retrievalService.search(seriesId, body, user));
        } catch (RejectedExecutionException e) {
            throw new TooManyRequestsException("检索繁忙，请稍后重试");
        }
        try {
            return ApiResponse.ok(future.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检索被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("检索失败", e.getCause());
        }
    }
}
