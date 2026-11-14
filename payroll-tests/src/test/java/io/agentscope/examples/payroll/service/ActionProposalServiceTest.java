package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.domain.PayrollGoneException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.support.Permissions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActionProposalServiceTest {

    private static final long USER = 7L;
    private static final long OTHER_USER = 8L;
    private static final long SESSION = 1001L;

    private H2ChatStore chatStore;
    private H2ActionProposalStore proposals;
    private DefaultActionExecutor executor;
    private ActionProposalService service;
    private AtomicInteger executions;

    @BeforeEach
    void setUp() {
        PayrollDatabase database =
                PayrollDatabase.inMemory("action-proposal-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        proposals = new H2ActionProposalStore(database);
        executions = new AtomicInteger();
        executor =
                new DefaultActionExecutor(
                        Map.of(
                                "disable_employee",
                                (params, userId) -> {
                                    executions.incrementAndGet();
                                    return Map.of("status", "OK", "employee", params.get("id"));
                                },
                                "explode",
                                (params, userId) -> {
                                    executions.incrementAndGet();
                                    throw new IllegalStateException("远端服务不可用");
                                }),
                        Map.of("disable_employee", Permissions.EMPLOYEE_WRITE),
                        List::of);
        service = new ActionProposalService(chatStore, proposals, executor, null);
    }

    @Test
    @DisplayName("发起人确认后执行一次并追加操作结果消息，重复确认保持幂等")
    void confirmExecutesOnceAndAppendsResultMessage() {
        ActionProposal proposal = seed("disable_employee", ActionProposal.STATUS_PENDING, future());

        ActionProposal executed = service.confirm(user(Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)), proposal.proposalId(), null);

        assertEquals(ActionProposal.STATUS_EXECUTED, executed.status());
        assertEquals(1, executions.get());
        List<ChatMessage> messages = chatStore.messages(SESSION);
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).content().startsWith("操作结果："));
        assertTrue(messages.get(1).content().contains("状态：EXECUTED"));

        ActionProposal again =
                service.confirm(
                        user(Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)),
                        proposal.proposalId(),
                        "retry-key");
        assertEquals(ActionProposal.STATUS_EXECUTED, again.status());
        assertEquals(1, executions.get());
        assertEquals(2, chatStore.messages(SESSION).size());
    }

    @Test
    @DisplayName("缺少工具权限时返回 403 且提案保持待确认，不执行也不落结果消息")
    void rejectsConfirmWithoutPermission() {
        ActionProposal proposal = seed("disable_employee", ActionProposal.STATUS_PENDING, future());

        assertThrows(
                PayrollForbiddenException.class,
                () -> service.confirm(user(Set.of(Permissions.CHAT_USE)), proposal.proposalId(), null));

        assertEquals(
                ActionProposal.STATUS_PENDING,
                proposals.find(proposal.proposalId()).orElseThrow().status());
        assertEquals(0, executions.get());
        assertEquals(1, chatStore.messages(SESSION).size());
    }

    @Test
    @DisplayName("过期提案落 EXPIRED 并返回 410，历史查询同样先过期再展示")
    void expiresProposalAndReflectsInHistory() {
        ActionProposal proposal =
                seed(
                        "disable_employee",
                        ActionProposal.STATUS_PENDING,
                        LocalDateTime.now().minusSeconds(1));

        assertThrows(
                PayrollGoneException.class,
                () -> service.confirm(
                        user(Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)),
                        proposal.proposalId(),
                        null));
        assertEquals(
                ActionProposal.STATUS_EXPIRED,
                proposals.find(proposal.proposalId()).orElseThrow().status());
        assertEquals(0, executions.get());

        ActionProposal second =
                seed(
                        "disable_employee",
                        ActionProposal.STATUS_PENDING,
                        LocalDateTime.now().minusSeconds(1));
        ChatMessage message =
                chatStore.messages(SESSION).stream()
                        .filter(item -> !item.proposals().isEmpty())
                        .reduce((left, right) -> right)
                        .orElseThrow();
        assertEquals(ActionProposal.STATUS_EXPIRED, message.proposals().get(0).status());
        assertEquals(second.proposalId(), message.proposals().get(0).proposalId());
    }

    @Test
    @DisplayName("非发起人查看或确认按 404 处理")
    void hidesProposalFromOtherUsers() {
        ActionProposal proposal = seed("disable_employee", ActionProposal.STATUS_PENDING, future());

        assertThrows(
                PayrollNotFoundException.class, () -> service.view(OTHER_USER, proposal.proposalId()));
        assertThrows(
                PayrollNotFoundException.class,
                () -> service.confirm(
                        new AuthUser(
                                OTHER_USER,
                                "other",
                                "其他",
                                "HR",
                                "HR",
                                null,
                                null,
                                Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)),
                        proposal.proposalId(),
                        null));
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("取消只作用于待确认提案，执行失败落 FAILED 并写入原因")
    void cancelsPendingAndRecordsFailures() {
        ActionProposal canceled = seed("disable_employee", ActionProposal.STATUS_PENDING, future());
        assertEquals(
                ActionProposal.STATUS_CANCELED,
                service.cancel(USER, canceled.proposalId()).status());
        assertEquals(
                ActionProposal.STATUS_CANCELED,
                service.confirm(
                                user(Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)),
                                canceled.proposalId(),
                                null)
                        .status());
        assertEquals(0, executions.get());

        ActionProposal failing = seed("explode", ActionProposal.STATUS_PENDING, future());
        ActionProposal failed =
                service.confirm(
                        user(Set.of(Permissions.CHAT_USE, Permissions.EMPLOYEE_WRITE)),
                        failing.proposalId(),
                        null);
        assertEquals(ActionProposal.STATUS_FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("远端服务不可用"));
        ChatMessage last = chatStore.messages(SESSION).get(chatStore.messages(SESSION).size() - 1);
        assertTrue(last.content().contains("状态：FAILED"));
        assertTrue(last.content().contains("远端服务不可用"));
    }

    private ActionProposal seed(String tool, String status, LocalDateTime expiresAt) {
        String proposalId = UUID.randomUUID().toString();
        ActionProposal proposal =
                new ActionProposal(
                        proposalId,
                        USER,
                        SESSION,
                        0L,
                        "AGENT",
                        tool,
                        "{\"id\":\"E-001\"}",
                        "禁用员工 E-001",
                        ActionProposal.RISK_NORMAL,
                        "DEFAULT_MUTATING",
                        status,
                        proposalId,
                        "hash",
                        LocalDateTime.now(),
                        expiresAt,
                        0L,
                        null,
                        null,
                        null,
                        null);
        chatStore.appendAssistant(
                SESSION, "需要确认后执行。", List.of(), "test-model", List.of(), null, List.of(proposal));
        return proposals.find(proposalId).orElseThrow();
    }

    private static AuthUser user(Set<String> permissions) {
        return new AuthUser(USER, "user", "用户", "HR", "HR", null, null, permissions);
    }

    private static LocalDateTime future() {
        return LocalDateTime.now().plusMinutes(5);
    }
}
