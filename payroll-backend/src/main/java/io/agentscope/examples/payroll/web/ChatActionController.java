package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.ActionProposalService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 写操作提案的会话内确认入口：仅发起人可见、可确认或取消。 */
@RestController
@RequestMapping("/api/chat/actions")
@RequirePermission(Permissions.CHAT_USE)
public class ChatActionController {

    private final ActionProposalService actionProposalService;
    private final AuthService authService;

    public ChatActionController(
            ActionProposalService actionProposalService, AuthService authService) {
        this.actionProposalService = actionProposalService;
        this.authService = authService;
    }

    @GetMapping("/{proposalId}")
    public ApiResponse view(@PathVariable String proposalId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(actionProposalService.view(user.id(), proposalId));
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse confirm(
            @PathVariable String proposalId,
            @RequestBody(required = false) DecisionRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        String decision = body == null ? null : body.decision();
        if (decision == null || decision.isBlank()) {
            throw new PayrollException("请选择确认或取消");
        }
        String normalized = decision.trim().toUpperCase();
        return switch (normalized) {
            case "CONFIRM" ->
                    ApiResponse.ok(
                            actionProposalService.confirm(
                                    user, proposalId, body.idempotencyKey()));
            case "CANCEL" ->
                    ApiResponse.ok(actionProposalService.cancel(user.id(), proposalId));
            default -> throw new PayrollException("不支持的操作决定：" + decision);
        };
    }

    public record DecisionRequest(String decision, String idempotencyKey) {
    }
}
