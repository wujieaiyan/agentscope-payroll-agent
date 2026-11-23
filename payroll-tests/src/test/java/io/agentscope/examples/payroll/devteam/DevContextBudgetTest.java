package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 上下文预算：按模型窗口扣除生成预留，压缩触发点与保留量保持递减关系。 */
class DevContextBudgetTest {

    @Test
    @DisplayName("编码模型 32k 窗口：扣除 4k 生成预留后按 80% 触发压缩")
    void derivesFromCodingModel() {
        DevModelSettings model =
                new DevModelSettings("ollama:qwen2.5-coder:14b", null, 32_768, 4_096, 0.2D, 300);

        DevContextBudget budget = DevContextBudget.of(model);

        assertEquals(32_768, budget.maxContextTokens());
        assertEquals(22_938, budget.compactionTriggerTokens());
        assertEquals(7_168, budget.compactionKeepTokens());
        assertEquals(budget.compactionTriggerTokens(), budget.promptBudgetTokens());
        assertEquals(24_000, budget.toolResultMaxChars());
        assertEquals(2_000, budget.toolResultPreviewChars());
        assertTrue(budget.compactionKeepTokens() < budget.compactionTriggerTokens());
    }

    @Test
    @DisplayName("控制面 8k 窗口：触发点仍低于可用窗口，保留量不低于下限")
    void derivesFromSmallControlModel() {
        DevContextBudget budget = DevContextBudget.of(8_192, 1_024);

        assertEquals(5_734, budget.compactionTriggerTokens());
        assertEquals(1_792, budget.compactionKeepTokens());
        assertTrue(budget.compactionTriggerTokens() < budget.maxContextTokens());
        assertEquals(40, budget.compactionTriggerMessages());
    }

    @Test
    @DisplayName("过小窗口抬到可用下限，生成预留大于窗口时不让触发点溢出")
    void clampsTinyWindows() {
        DevContextBudget budget = DevContextBudget.of(2_048, 8_192);

        assertEquals(4_096, budget.compactionTriggerTokens());
        assertEquals(1_024, budget.compactionKeepTokens());
    }

    @Test
    @DisplayName("编码模型未配置或窗口不足 32k 时拒绝计算")
    void rejectsUnavailableCodingModel() {
        assertThrows(
                PayrollException.class,
                () -> DevContextBudget.of(new DevModelSettings("", null, 0, 0, 0D, 0)));
        assertThrows(
                PayrollException.class,
                () ->
                        DevContextBudget.of(
                                new DevModelSettings(
                                        "ollama:qwen2.5-coder:7b", null, 8_192, 4_096, 0.2D, 300)));
    }
}
