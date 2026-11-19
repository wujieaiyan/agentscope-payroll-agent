package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.devteam.DevModelSettings;
import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.Test;

/**
 * P1-6：编码面模型隔离。只读/写模式的选择必须 fail-closed，不触碰网络（未配置时直接判定）。
 */
class DevModelSelectionTest {

    @Test
    void devModelNullForMissingSettings() {
        assertNull(PayrollAgentFactory.devModel(null));
    }

    @Test
    void devModelNullWhenNotConfigured() {
        assertNull(
                PayrollAgentFactory.devModel(
                        new DevModelSettings("", null, 32_768, 4_096, 0.2D, 300)));
    }

    @Test
    void devModelNullWhenContextBelowMinimum() {
        assertNull(
                PayrollAgentFactory.devModel(
                        new DevModelSettings("ollama:qwen2.5-coder:7b", null, 8_192, 1_024, 0.2D, 300)));
    }

    @Test
    void writableTaskRequiresDedicatedModel() {
        DevModelSettings missing = new DevModelSettings("", null, 0, 0, 0D, 0);
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> PayrollAgentFactory.selectDevModel(missing, true));
        assertTrue(error.getMessage().contains(DevModelSettings.ENV_MODEL));
        assertTrue(error.getMessage().contains("无法执行写代码任务"));
    }

    @Test
    void writableTaskRejectsSmallContext() {
        DevModelSettings small =
                new DevModelSettings("ollama:qwen2.5-coder:7b", null, 8_192, 1_024, 0.2D, 300);
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> PayrollAgentFactory.selectDevModel(small, true));
        assertTrue(error.getMessage().contains("编码能力关闭"));
    }
}
