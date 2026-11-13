package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
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

    public KbRetrievalController(KbRetrievalService retrievalService, AuthService authService) {
        this.retrievalService = retrievalService;
        this.authService = authService;
    }

    @PostMapping("/test")
    public ApiResponse test(
            @PathVariable long seriesId,
            @RequestBody RetrievalTestRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(retrievalService.search(seriesId, body, user));
    }
}
