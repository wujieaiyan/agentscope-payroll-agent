package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosRuntimeSettingsTest {

    private FakeNacosServer nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("新文档缺失时回退旧的两份设置文档")
    void fallsBackToLegacyDocs() throws IOException {
        nacos = new FakeNacosServer();
        nacos.configs.put(
                NacosRuntimeSettings.LEGACY_AGENT_DATA_ID,
                "{\"calculationAgentId\":\"agent-x\",\"actionConfirm\":true}");
        nacos.configs.put(NacosRuntimeSettings.LEGACY_FLOW_DATA_ID, "{\"activeFlowId\":\"\"}");

        NacosRuntimeSettings.Snapshot snapshot = settings().snapshot();

        assertEquals(NacosRuntimeSettings.MODE_AGENT, snapshot.mode());
        assertEquals("agent-x", snapshot.agentId());
        assertEquals("", snapshot.flowId());
        assertEquals(Boolean.TRUE, snapshot.actionConfirm());
        assertEquals(ConfigRevision.ABSENT, snapshot.revision());
    }

    @Test
    @DisplayName("新文档存在时优先于旧文档，模式与 ids 保持一致")
    void newDocWinsAndNormalizesMode() throws IOException {
        nacos = new FakeNacosServer();
        nacos.configs.put(
                NacosRuntimeSettings.LEGACY_AGENT_DATA_ID,
                "{\"calculationAgentId\":\"legacy-agent\"}");
        nacos.configs.put(
                NacosRuntimeSettings.DATA_ID,
                "{\"mode\":\"FLOW\",\"flowId\":\"flow-a\",\"agentId\":\"stale-agent\"}");

        NacosRuntimeSettings.Snapshot snapshot = settings().snapshot();

        assertEquals(NacosRuntimeSettings.MODE_FLOW, snapshot.mode());
        assertEquals("flow-a", snapshot.flowId());
        assertEquals("", snapshot.agentId(), "FLOW 模式下不应同时保留智能体");
    }

    @Test
    @DisplayName("首次写入把旧文档内容迁入新文档，旧文档保持只读")
    void firstWriteMigratesLegacyDocs() throws IOException {
        nacos = new FakeNacosServer();
        String legacyAgent =
                "{\"calculationAgentId\":\"agent-x\",\"actionConfirm\":true}";
        nacos.configs.put(NacosRuntimeSettings.LEGACY_AGENT_DATA_ID, legacyAgent);
        nacos.configs.put(NacosRuntimeSettings.LEGACY_FLOW_DATA_ID, "{\"activeFlowId\":\"\"}");

        NacosRuntimeSettings settings = settings();
        settings.activateFlow("flow-a", null);

        String migrated = nacos.configs.get(NacosRuntimeSettings.DATA_ID);
        assertTrue(migrated.contains("\"mode\" : \"FLOW\""), migrated);
        assertTrue(migrated.contains("\"flowId\" : \"flow-a\""), migrated);
        assertTrue(migrated.contains("\"actionConfirm\" : true"), migrated);
        assertEquals(1, nacos.writes.get());
        assertEquals(legacyAgent, nacos.configs.get(NacosRuntimeSettings.LEGACY_AGENT_DATA_ID));
    }

    @Test
    @DisplayName("切换流程与智能体各只发布一次，并互相清空")
    void switchingModePublishesOnce() throws IOException {
        nacos = new FakeNacosServer();
        NacosRuntimeSettings settings = settings();

        settings.activateAgent("agent-a", null);
        assertEquals(1, nacos.writes.get());
        assertEquals("agent-a", settings.activeAgentId());
        assertEquals("", settings.activeFlowId());

        settings.activateFlow("flow-a", null);
        assertEquals(2, nacos.writes.get());
        assertEquals("flow-a", settings.activeFlowId());
        assertEquals("", settings.activeAgentId());
        String doc = nacos.configs.get(NacosRuntimeSettings.DATA_ID);
        assertTrue(doc.contains("\"mode\" : \"FLOW\""), doc);
        assertFalse(doc.contains("agent-a"), doc);
    }

    @Test
    @DisplayName("清除非当前模式不产生写入")
    void clearOnlyTouchesCurrentMode() throws IOException {
        nacos = new FakeNacosServer();
        NacosRuntimeSettings settings = settings();
        settings.activateAgent("agent-a", null);
        int writes = nacos.writes.get();

        settings.clearFlow(null);
        assertEquals(writes, nacos.writes.get());

        settings.clearAgent(null);
        assertEquals(writes + 1, nacos.writes.get());
        assertEquals(NacosRuntimeSettings.MODE_PIPELINE, settings.snapshot().mode());
    }

    @Test
    @DisplayName("revision 不一致时拒绝写入")
    void staleRevisionConflicts() throws IOException {
        nacos = new FakeNacosServer();
        NacosRuntimeSettings settings = settings();
        settings.activateAgent("agent-a", null);

        assertThrows(
                PayrollConflictException.class,
                () -> settings.activateFlow("flow-a", "stale-revision"));
        assertEquals("agent-a", settings.activeAgentId());
    }

    @Test
    @DisplayName("旧文档读取失败时按 503 处理，不用空基线覆盖设置")
    void legacyReadFailureFailsWrite() throws IOException {
        nacos = new FakeNacosServer();
        nacos.statusForDataId.put(NacosRuntimeSettings.LEGACY_AGENT_DATA_ID, 500);
        NacosRuntimeSettings settings = settings();

        assertThrows(RuntimeException.class, () -> settings.activateAgent("agent-a", null));
        assertEquals(0, nacos.writes.get());
    }

    @Test
    @DisplayName("Nacos 未启用时固定 PIPELINE 且不可写")
    void disabledSettingsAreReadOnly() {
        NacosRuntimeSettings settings =
                new NacosRuntimeSettings(
                        new NacosSkillSettings(
                                false,
                                "http://127.0.0.1:8848",
                                "payroll-agent",
                                "",
                                "",
                                "",
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(60)));

        assertEquals(NacosRuntimeSettings.MODE_PIPELINE, settings.snapshot().mode());
        assertEquals("", settings.activeAgentId());
        assertThrows(RuntimeException.class, () -> settings.activateAgent("agent-a", null));
    }

    private NacosRuntimeSettings settings() {
        return new NacosRuntimeSettings(
                new NacosSkillSettings(
                        true,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(60)));
    }
}
