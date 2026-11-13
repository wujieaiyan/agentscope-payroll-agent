package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ActionProposal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolSafetyTest {

    private final ToolSafety defaults = ToolSafety.defaults();

    @Test
    @DisplayName("内置工具读取 @Tool(readOnly) 注解，只读不生成提案")
    void builtinToolsUseAnnotation() {
        ToolSafety.Decision readOnly =
                defaults.classify("calculate_payroll", false, false, false, Map.of());

        assertTrue(readOnly.readOnly());
        assertEquals(ToolSafety.SOURCE_HINT_READ_ONLY, readOnly.source());

        ToolSafety.Decision missing =
                defaults.classify("not_a_builtin", false, false, false, Map.of());
        assertFalse(missing.readOnly());
        assertEquals(ToolSafety.SOURCE_DEFAULT_MUTATING, missing.source());
    }

    @Test
    @DisplayName("MCP 只读声明三态：true 放行，false 与未声明视为写")
    void mcpReadOnlyHintTriState() {
        assertTrue(defaults.classify("doc_search", true, true, false, Map.of()).readOnly());
        assertFalse(defaults.classify("doc_delete", true, false, false, Map.of()).readOnly());
        assertFalse(defaults.classify("doc_unknown", true, false, false, Map.of()).readOnly());
    }

    @Test
    @DisplayName("destructiveHint 提升为不可撤销，金额参数标记金额风险")
    void derivesRiskLevel() {
        ToolSafety.Decision destructive =
                defaults.classify("drop_table", true, false, true, Map.of());
        assertEquals(ActionProposal.RISK_IRREVERSIBLE, destructive.riskLevel());

        ToolSafety.Decision amount =
                defaults.classify("adjust_salary", false, false, false, Map.of("bonus", 5000));
        assertEquals(ActionProposal.RISK_AMOUNT, amount.riskLevel());

        ToolSafety.Decision plain =
                defaults.classify("disable_employee", false, false, false, Map.of("id", "E-1"));
        assertEquals(ActionProposal.RISK_NORMAL, plain.riskLevel());
    }

    @Test
    @DisplayName("优先级：本地覆盖 > 服务端声明 > 默认写")
    void overrideWinsOverHints() {
        ToolSafety safety =
                new ToolSafety(
                        Map.of("report_export", "MUTATING", "evil_tool", "READ_ONLY"),
                        true,
                        Map.of());

        assertEquals(ToolSafety.SOURCE_OVERRIDE, safety.classify("report_export", false, true, false, Map.of()).source());
        assertFalse(safety.classify("report_export", false, true, false, Map.of()).readOnly());
        assertTrue(safety.classify("evil_tool", true, false, true, Map.of()).readOnly());
        assertTrue(safety.overridden("evil_tool"));
    }

    @Test
    @DisplayName("trust-read-only-hints=false 时所有 MCP 工具都视为写")
    void untrustedHintsForceMutating() {
        ToolSafety safety =
                new ToolSafety(Map.of(), false, Map.of("doc_search", true));

        assertFalse(safety.classify("doc_search", true, true, false, Map.of()).readOnly());
        assertFalse(safety.classify("doc_list", true, true, false, Map.of()).readOnly());
    }

    @Test
    @DisplayName("配置的 readOnlyTools 可放行未声明 hint 的 MCP 工具")
    void mcpReadOnlyConfigAllowsTool() {
        ToolSafety safety =
                new ToolSafety(Map.of(), true, Map.of("doc_search", true));

        assertTrue(safety.classify("doc_search", true, false, false, Map.of()).readOnly());
        assertFalse(safety.classify("doc_write", true, false, false, Map.of()).readOnly());
    }

    @Test
    @DisplayName("只读工具经覆盖提为写后进入确认")
    void overrideCanMakeReadOnlyToolMutating() {
        ToolSafety safety = new ToolSafety(Map.of("calculate_payroll", "MUTATING"), true, Map.of());

        ToolSafety.Decision decision =
                safety.classify("calculate_payroll", false, true, false, Map.of());

        assertFalse(decision.readOnly());
        assertEquals(ToolSafety.SOURCE_OVERRIDE, decision.source());
    }

    @Test
    @DisplayName("非法覆盖值被忽略，不影响默认分类")
    void ignoresInvalidOverride() {
        ToolSafety safety = new ToolSafety(Map.of("calculate_payroll", "MAYBE"), true, Map.of());

        assertFalse(safety.overridden("calculate_payroll"));
        assertTrue(safety.classify("calculate_payroll", false, true, false, Map.of()).readOnly());
    }

    @Test
    @DisplayName("启动审计可读取生效覆盖与 MCP 只读配置")
    void exposesEffectiveSettingsForAudit() {
        ToolSafety safety =
                new ToolSafety(Map.of("a", "READ_ONLY"), true, Map.of("b", true));

        assertEquals(Map.of("a", true), safety.overrides());
        assertEquals(Map.of("b", true), safety.mcpReadOnlyTools());
    }
}
