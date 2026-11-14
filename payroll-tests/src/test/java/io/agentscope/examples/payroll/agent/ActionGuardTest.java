package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActionGuardTest {

    private final List<ActionProposal> events = new ArrayList<>();

    private final ChatStreamListener listener =
            new ChatStreamListener() {
                @Override
                public void onActionProposal(ActionProposal proposal) {
                    events.add(proposal);
                }
            };

    @Test
    @DisplayName("内置只读工具直接放行，不生成提案")
    void readOnlyBuiltinPassesThrough() {
        ActionGuard guard = guard(ToolSafety.defaults(), true);

        assertNull(guard.intercept("calculate_payroll", Map.of("employee_id", "EMP001"), false, true, false));
        assertFalse(guard.hasProposal());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("第一个写工具生成待确认提案并推送事件")
    void firstMutatingToolBecomesProposal() {
        ActionGuard guard = guard(ToolSafety.defaults(), true);

        String result =
                guard.intercept("disable_employee", Map.of("id", "E-001"), false, false, false);

        assertNotNull(result);
        Map<String, Object> payload =
                JsonSupport.fromJson(result, new TypeReference<Map<String, Object>>() {});
        assertEquals("PENDING_CONFIRM", payload.get("status"));
        ActionProposal proposal = guard.firstProposal();
        assertEquals("disable_employee", proposal.toolName());
        assertEquals(ActionProposal.STATUS_PENDING, proposal.status());
        assertEquals(proposal.proposalId(), payload.get("proposal_id"));
        assertEquals(List.of(proposal), events);
        assertTrue(proposal.expiresAt().isAfter(proposal.createdAt()));
    }

    @Test
    @DisplayName("同轮后续写工具被拒绝，只保留第一个提案")
    void rejectsSubsequentWritesInSameRound() {
        ActionGuard guard = guard(ToolSafety.defaults(), true);

        guard.intercept("disable_employee", Map.of("id", "E-001"), false, false, false);
        String second = guard.intercept("delete_employee", Map.of("id", "E-002"), false, false, false);

        Map<String, Object> payload =
                JsonSupport.fromJson(second, new TypeReference<Map<String, Object>>() {});
        assertEquals(
                guard.firstProposal().proposalId(), payload.get("proposal_id"));
        assertEquals(1, guard.proposals().size());
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("确认开关关闭时按旧行为放行写工具")
    void disabledGuardPassesWritesThrough() {
        ActionGuard guard = guard(ToolSafety.defaults(), false);

        assertNull(guard.intercept("disable_employee", Map.of(), false, false, false));
        assertFalse(guard.hasProposal());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("全局开关关闭时知识库写工具仍强制生成提案")
    void kbWritesAlwaysConfirmWhenDisabled() {
        ActionGuard guard = guard(ToolSafety.defaults(), false);

        String result =
                guard.intercept("kb_save_config", Map.of("series_id", 7), false, false, false);

        assertNotNull(result);
        Map<String, Object> payload =
                JsonSupport.fromJson(result, new TypeReference<Map<String, Object>>() {});
        assertEquals("PENDING_CONFIRM", payload.get("status"));
        assertEquals("kb_save_config", guard.firstProposal().toolName());
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("强制清单不区分大小写，且不包含普通写工具")
    void alwaysConfirmListIsCaseInsensitiveAndNarrow() {
        ActionGuard guard =
                guard(
                        ToolSafety.defaults(),
                        false,
                        java.util.Set.of("KB_Save_Config "));

        assertNotNull(
                guard.intercept("Kb_Save_Config", Map.of("series_id", 7), false, false, false));
        assertNull(guard.intercept("disable_employee", Map.of(), false, false, false));
        assertEquals(java.util.Set.of("kb_save_config"), guard.alwaysConfirmTools());
    }

    @Test
    @DisplayName("本地覆盖为只读时即使服务端声明修改也放行")
    void overrideReadOnlyWins() {
        ToolSafety safety =
                new ToolSafety(Map.of("evil_tool", "READ_ONLY"), true, Map.of());
        ActionGuard guard = guard(safety, true);

        assertNull(guard.intercept("evil_tool", Map.of("amount", 100), true, false, true));
        assertFalse(guard.hasProposal());
    }

    @Test
    @DisplayName("trust-read-only-hints=false 时 MCP 只读声明不再生效")
    void untrustedHintsForceConfirmation() {
        ActionGuard guard =
                guard(new ToolSafety(Map.of(), false, Map.of("lookup_doc", true)), true);

        assertNotNull(guard.intercept("lookup_doc", Map.of("q", "制度"), true, true, false));
        assertTrue(guard.hasProposal());
    }

    @Test
    @DisplayName("配置层 readOnlyTools 可强制 MCP 工具只读")
    void mcpReadOnlyConfigPassesThrough() {
        ActionGuard guard =
                guard(new ToolSafety(Map.of(), true, Map.of("lookup_doc", true)), true);

        assertNull(guard.intercept("lookup_doc", Map.of("q", "制度"), true, false, false));
        assertFalse(guard.hasProposal());
    }

    @Test
    @DisplayName("destructiveHint 标记为不可撤销，金额参数标记金额风险")
    void classifiesRiskLevel() {
        ActionGuard destructive = guard(ToolSafety.defaults(), true);
        destructive.intercept("drop_table", Map.of("table", "employee"), true, false, true);
        assertEquals(ActionProposal.RISK_IRREVERSIBLE, destructive.firstProposal().riskLevel());

        ActionGuard amount = guard(ToolSafety.defaults(), true);
        amount.intercept("adjust_salary", Map.of("amount", 5000), false, false, false);
        assertEquals(ActionProposal.RISK_AMOUNT, amount.firstProposal().riskLevel());
    }

    @Test
    @DisplayName("提案前的正文被保留，提案后不再累积并生成服务端收尾文案")
    void keepsTextBeforeProposalOnly() {
        ActionGuard guard = guard(ToolSafety.defaults(), true);

        guard.observeText("张伟本月工资已");
        guard.observeText("核算完成。");
        guard.intercept("disable_employee", Map.of("id", "E-001"), false, false, false);
        guard.observeText("已执行禁用。");

        assertEquals("张伟本月工资已核算完成。", guard.textBeforeProposal());
        assertTrue(guard.finalReplyTemplate().contains("请在下方卡片确认后执行"));
        assertFalse(guard.finalReplyTemplate().contains("已执行禁用"));
    }

    private ActionGuard guard(ToolSafety safety, boolean enabled) {
        return new ActionGuard(7L, 1001L, "AGENT", enabled, 15, safety, listener);
    }

    private ActionGuard guard(ToolSafety safety, boolean enabled, java.util.Set<String> alwaysConfirmTools) {
        return new ActionGuard(
                7L, 1001L, "AGENT", enabled, 15, safety, listener, alwaysConfirmTools);
    }
}
