package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.domain.PayrollGoneException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.support.ToolTraceExtractor;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提案的查询、确认与取消：归属/权限/过期校验在条件更新之前完成，
 * 条件 UPDATE 决定唯一执行权，重复点击与多标签页天然幂等。
 */
public final class ActionProposalService {

    private static final Logger log = LoggerFactory.getLogger(ActionProposalService.class);
    private static final String EXECUTOR_MODEL = "action-executor";

    private final H2ChatStore chatStore;
    private final H2ActionProposalStore proposals;
    private final ActionExecutor executor;
    private final AuditService audit;

    public ActionProposalService(
            H2ChatStore chatStore,
            H2ActionProposalStore proposals,
            ActionExecutor executor,
            AuditService audit) {
        this.chatStore = chatStore;
        this.proposals = proposals;
        this.executor = executor;
        this.audit = audit;
    }

    /** 查看提案；不存在或不属于当前用户时按 404 处理，过期则先落 EXPIRED。 */
    public ActionProposal view(long userId, String proposalId) {
        ActionProposal proposal = requireOwned(userId, proposalId);
        return expireIfDue(proposal);
    }

    public ActionProposal confirm(
            AuthUser user, String proposalId, String idempotencyKey) {
        ActionProposal proposal = expireIfDue(requireOwned(user.id(), proposalId));
        if (!proposal.pending()) {
            return proposal;
        }
        String permission = executor.requiredPermission(proposal.toolName());
        if (!user.can(permission)) {
            throw new PayrollForbiddenException("当前账号缺少执行该操作所需的权限：" + permission);
        }
        String key =
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? proposal.proposalId()
                        : idempotencyKey.trim();
        if (!proposals.confirmPending(proposalId, user.id(), key)) {
            log.info(
                    "[写操作] 确认竞争失败，返回当前状态：proposalId={}，userId={}",
                    proposalId,
                    user.id());
            return requireOwned(user.id(), proposalId);
        }
        audit(
                "chat.action.confirm",
                proposal,
                Map.of("idempotency_key", key, "confirmed_by", user.id()));

        ActionProposal confirmed = requireOwned(user.id(), proposalId);
        long startedAt = System.nanoTime();
        try {
            Object result = executor.execute(confirmed, user.id());
            String resultJson = JsonSupport.toJson(result);
            proposals.markExecuted(proposalId, resultJson);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            auditExecute(confirmed, ActionProposal.STATUS_EXECUTED, resultJson, null);
            appendResultMessage(
                    confirmed,
                    ActionProposal.STATUS_EXECUTED,
                    resultJson,
                    null,
                    elapsedMs);
            log.info(
                    "[写操作] 提案执行完成：proposalId={}，tool={}，耗时={}ms",
                    proposalId,
                    confirmed.toolName(),
                    elapsedMs);
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            proposals.markFailed(proposalId, message);
            auditExecute(confirmed, ActionProposal.STATUS_FAILED, null, message);
            appendResultMessage(
                    confirmed, ActionProposal.STATUS_FAILED, null, message, elapsedMs);
            log.warn(
                    "[写操作] 提案执行失败：proposalId={}，tool={}，原因={}",
                    proposalId,
                    confirmed.toolName(),
                    message);
        }
        return requireOwned(user.id(), proposalId);
    }

    /** 取消只作用于 PENDING；已进入 CONFIRMED 的提案不允许撤回。 */
    public ActionProposal cancel(long userId, String proposalId) {
        ActionProposal proposal = expireIfDue(requireOwned(userId, proposalId));
        if (!proposal.pending()) {
            return proposal;
        }
        if (proposals.cancelPending(proposalId)) {
            audit("chat.action.cancel", proposal, Map.of("status", ActionProposal.STATUS_CANCELED));
            log.info("[写操作] 提案已取消：proposalId={}，userId={}", proposalId, userId);
        }
        return requireOwned(userId, proposalId);
    }

    private ActionProposal requireOwned(long userId, String proposalId) {
        ActionProposal proposal =
                proposals
                        .find(proposalId)
                        .orElseThrow(() -> new PayrollNotFoundException("提案不存在"));
        if (proposal.userId() != userId) {
            throw new PayrollNotFoundException("提案不存在");
        }
        return proposal;
    }

    private ActionProposal expireIfDue(ActionProposal proposal) {
        LocalDateTime now = LocalDateTime.now();
        if (!proposal.expiredAt(now)) {
            return proposal;
        }
        if (proposals.markExpired(proposal.proposalId())) {
            audit("chat.action.expire", proposal, Map.of("expires_at", proposal.expiresAt()));
            log.info(
                    "[写操作] 提案已过期：proposalId={}，expiresAt={}",
                    proposal.proposalId(),
                    proposal.expiresAt());
        }
        ActionProposal refreshed =
                proposals.find(proposal.proposalId()).orElseThrow(
                        () -> new PayrollNotFoundException("提案不存在"));
        if (ActionProposal.STATUS_EXPIRED.equals(refreshed.status())) {
            throw new PayrollGoneException("提案已过期，请重新发起操作");
        }
        return refreshed;
    }

    private void auditExecute(
            ActionProposal proposal, String status, String resultJson, String errorMessage) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("tool_name", proposal.toolName());
        after.put("risk_level", proposal.riskLevel());
        if (resultJson != null) {
            after.put("result", parseResult(resultJson));
        }
        if (errorMessage != null) {
            after.put("error", errorMessage);
        }
        audit("chat.action.execute", proposal, after);
    }

    private void audit(String action, ActionProposal proposal, Map<String, Object> after) {
        if (auditService() == null) {
            return;
        }
        try {
            auditService()
                    .record(action, "chat_action_proposal", proposal.proposalId(), null, after);
        } catch (RuntimeException e) {
            log.warn(
                    "[审计] 记录 {} 失败：proposalId={}，原因={}",
                    action,
                    proposal.proposalId(),
                    rootMessage(e));
        }
    }

    private AuditService auditService() {
        return audit;
    }

    /** 执行结果追加为普通助手消息，不触发新的模型调用。 */
    private void appendResultMessage(
            ActionProposal proposal,
            String status,
            String resultJson,
            String errorMessage,
            long elapsedMs) {
        try {
            ToolTraceExtractor.Evidence evidence =
                    ToolTraceExtractor.extract(proposal.paramsJson(), resultJson);
            ChatToolTrace trace =
                    ChatToolTrace.draft(
                            proposal.proposalId(),
                            proposal.toolName(),
                            "action-executor",
                            proposal.paramsJson(),
                            resultJson,
                            false,
                            ActionProposal.STATUS_EXECUTED.equals(status) ? "SUCCESS" : "ERROR",
                            elapsedMs,
                            evidence.employees(),
                            evidence.period(),
                            evidence.department(),
                            evidence.amounts());
            String content = resultText(proposal, status, resultJson, errorMessage);
            ChatRouting routing =
                    new ChatRouting(
                            ChatRouting.ACTION,
                            proposal.agentPath(),
                            "提案确认后由服务端执行，未触发模型调用。",
                            null,
                            null,
                            EXECUTOR_MODEL,
                            "action:" + proposal.toolName(),
                            List.of(),
                            0L,
                            elapsedMs,
                            List.of());
            chatStore.appendAssistant(
                    proposal.sessionId(),
                    content,
                    List.of(proposal.toolName()),
                    EXECUTOR_MODEL,
                    List.of(trace),
                    routing,
                    List.of());
        } catch (RuntimeException e) {
            log.warn(
                    "[写操作] 追加操作结果消息失败：proposalId={}，原因={}",
                    proposal.proposalId(),
                    rootMessage(e));
        }
    }

    private static String resultText(
            ActionProposal proposal,
            String status,
            String resultJson,
            String errorMessage) {
        StringBuilder text = new StringBuilder();
        text.append("操作结果：").append(proposal.summary()).append('\n');
        text.append("状态：").append(status);
        if (errorMessage != null) {
            text.append("\n原因：").append(errorMessage);
        } else if (resultJson != null) {
            text.append("\n结果：").append(preview(resultJson));
        }
        return text.toString();
    }

    private static String preview(String json) {
        String compact = json.replaceAll("\\s+", " ");
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }

    private static Object parseResult(String json) {
        try {
            return JsonSupport.fromJson(json, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
