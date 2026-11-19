package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevTeamSettingsTest {

    private static final DevModelSettings CODING_MODEL =
            new DevModelSettings("ollama:qwen2.5-coder:14b", null, 32_768, 4_096, 0.2D, 300);

    @Test
    @DisplayName("只读未指定审批时按分析类默认 auto，写模式关闭")
    void readOnlyDefaultsToAuto() {
        DevTeamSettings settings = DevTeamSettings.of("read-only", "", CODING_MODEL);
        assertEquals(DevTeamMode.READ_ONLY, settings.mode());
        assertEquals(DevApprovalPolicy.AUTO, settings.approval());
        assertFalse(settings.writeEnabled());
        assertFalse(settings.degraded());
    }

    @Test
    @DisplayName("workspace-write + batch 且模型达标时写权限生效")
    void workspaceWriteBatchEnabled() {
        DevTeamSettings settings = DevTeamSettings.of("workspace-write", "batch", CODING_MODEL);
        assertEquals(DevTeamMode.WORKSPACE_WRITE, settings.mode());
        assertEquals(DevApprovalPolicy.BATCH, settings.approval());
        assertTrue(settings.writeEnabled());
        assertFalse(settings.degraded());
    }

    @Test
    @DisplayName("workspace-write 未指定审批时默认为 batch")
    void workspaceWriteDefaultsToBatch() {
        assertEquals(
                DevApprovalPolicy.BATCH,
                DevTeamSettings.of("workspace-write", null, CODING_MODEL).approval());
    }

    @Test
    @DisplayName("workspace-write + auto 是非法组合，启动即失败")
    void workspaceWriteRejectsAuto() {
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> DevTeamSettings.of("workspace-write", "auto", CODING_MODEL));
        assertTrue(error.getMessage().contains("workspace-write"));
        assertTrue(error.getMessage().contains("auto"));
    }

    @Test
    @DisplayName("danger-full-access 与未知取值直接拒绝")
    void rejectsUnsupportedModes() {
        assertThrows(
                PayrollException.class,
                () -> DevTeamSettings.of("danger-full-access", "batch", CODING_MODEL));
        assertThrows(
                PayrollException.class,
                () -> DevTeamSettings.of("unrestricted", "batch", CODING_MODEL));
        assertThrows(
                PayrollException.class,
                () -> DevTeamSettings.of("workspace-write", "sometimes", CODING_MODEL));
    }

    @Test
    @DisplayName("编码模型未配置时写模式降级为只读并记录原因")
    void degradesWhenDevModelMissing() {
        DevTeamSettings settings =
                DevTeamSettings.of(
                        "workspace-write",
                        "batch",
                        new DevModelSettings("", null, 0, 0, 0D, 0));
        assertEquals(DevTeamMode.WORKSPACE_WRITE, settings.requestedMode());
        assertEquals(DevTeamMode.READ_ONLY, settings.mode());
        assertTrue(settings.degraded());
        assertFalse(settings.writeEnabled());
        assertTrue(settings.disabledReason().contains(DevModelSettings.ENV_MODEL));
    }

    @Test
    @DisplayName("有效上下文低于 32k 时写模式同样降级")
    void degradesWhenContextTooSmall() {
        DevModelSettings small =
                new DevModelSettings("ollama:qwen2.5-coder:7b", null, 8_192, 4_096, 0.2D, 300);
        DevTeamSettings settings = DevTeamSettings.of("workspace-write", "per-task", small);
        assertTrue(settings.degraded());
        assertTrue(settings.disabledReason().contains("32768"));
    }

    @Test
    @DisplayName("per-task 在读写两种模式下都合法")
    void perTaskAllowedInBothModes() {
        assertEquals(
                DevApprovalPolicy.PER_TASK,
                DevTeamSettings.of("read-only", "per-task", CODING_MODEL).approval());
        assertTrue(
                DevTeamSettings.of("workspace-write", "per-task", CODING_MODEL).writeEnabled());
    }
}
