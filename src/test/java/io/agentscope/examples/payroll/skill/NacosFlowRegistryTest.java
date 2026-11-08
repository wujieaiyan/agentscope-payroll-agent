package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosFlowRegistryTest {

    private FakeNacosServer nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("未启用 Nacos 时只读并回退内置标准流程")
    void disabledIsReadOnly() {
        NacosFlowRegistry registry = new NacosFlowRegistry(settings(false));

        assertFalse(registry.writable());
        assertEquals("代码内置", registry.location());
        List<FlowDefinition> flows = registry.list();
        assertEquals(1, flows.size());
        assertEquals(NacosFlowRegistry.DEFAULT_FLOW_ID, flows.get(0).id());
        assertEquals("", registry.activeFlowId());
        assertNull(registry.activeFlow());

        PayrollException saveError =
                assertThrows(
                        PayrollException.class,
                        () -> registry.save(flow(null, "新流程"), "admin"));
        assertTrue(saveError.getMessage().contains("Nacos 未启用"), saveError.getMessage());
        assertThrows(
                PayrollException.class,
                () -> registry.setActiveFlow(NacosFlowRegistry.DEFAULT_FLOW_ID));
        assertThrows(
                PayrollException.class,
                () -> registry.delete(NacosFlowRegistry.DEFAULT_FLOW_ID));
    }

    @Test
    @DisplayName("Nacos 缺少流程配置时返回内置标准流程")
    void fallsBackWhenConfigMissing() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);

        List<FlowDefinition> flows = registry.list();

        assertEquals(1, flows.size());
        assertEquals(NacosFlowRegistry.DEFAULT_FLOW_ID, flows.get(0).id());
        assertTrue(registry.writable());
        assertTrue(registry.location().contains("payroll-agent"), registry.location());
        assertNull(registry.activeFlow(), "未设置默认流程时不应启用流程编排");
    }

    @Test
    @DisplayName("Nacos 配置损坏时回退内置标准流程")
    void malformedConfigFallsBack() throws IOException {
        nacos = new FakeNacosServer();
        nacos.configs.put(NacosFlowRegistry.DATA_ID, "{not-json");

        assertEquals(1, registry(true).list().size());
    }

    @Test
    @DisplayName("保存新流程写入 Nacos，新实例可重新读取")
    void savesNewFlowAndReloads() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);

        FlowDefinition saved = registry.save(flow(null, "我的流程"), "admin");

        assertNotNull(saved.id());
        assertTrue(saved.id().startsWith("flow-"), saved.id());
        assertEquals("admin", saved.createdBy());
        assertNotNull(saved.createdAt());
        assertTrue(nacos.configs.containsKey(NacosFlowRegistry.DATA_ID));

        List<FlowDefinition> reloaded = registry(true).list();
        assertEquals(2, reloaded.size());
        assertEquals("我的流程",
                reloaded.stream()
                        .filter(item -> item.id().equals(saved.id()))
                        .findFirst()
                        .orElseThrow()
                        .name());
    }

    @Test
    @DisplayName("更新保留创建人与创建时间，只刷新内容与更新时间")
    void updatePreservesCreatedMetadata() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);
        FlowDefinition saved = registry.save(flow(null, "旧名称"), "admin");

        FlowDefinition updated = registry.save(flow(saved.id(), "新名称"), "other");

        assertEquals(saved.id(), updated.id());
        assertEquals("新名称", updated.name());
        assertEquals("admin", updated.createdBy());
        assertEquals(saved.createdAt(), updated.createdAt());
        assertFalse(updated.updatedAt().isBefore(saved.createdAt()));
        assertEquals(2, registry.list().size());
    }

    @Test
    @DisplayName("设置默认流程写入 Nacos，新实例可读取")
    void setActiveFlowPersists() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);
        FlowDefinition saved = registry.save(flow(null, "默认候选"), "admin");

        registry.setActiveFlow(saved.id());

        assertEquals(saved.id(), registry.activeFlowId());
        assertEquals(saved.id(), registry.activeFlow().id());
        assertTrue(nacos.configs.get(NacosFlowRegistry.SETTINGS_DATA_ID).contains(saved.id()));

        NacosFlowRegistry reloaded = registry(true);
        assertEquals(saved.id(), reloaded.activeFlowId());
        assertEquals(saved.id(), reloaded.activeFlow().id());
    }

    @Test
    @DisplayName("不存在的流程不能设为默认")
    void setActiveFlowRejectsUnknown() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);

        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> registry.setActiveFlow("no-such-flow"));
        assertTrue(error.getMessage().contains("不存在"), error.getMessage());
        assertEquals("", registry.activeFlowId());
    }

    @Test
    @DisplayName("内置流程与默认流程不能删除")
    void deleteRejectsBuiltinAndActive() throws IOException {
        nacos = new FakeNacosServer();
        NacosFlowRegistry registry = registry(true);

        PayrollException builtin =
                assertThrows(
                        PayrollException.class,
                        () -> registry.delete(NacosFlowRegistry.DEFAULT_FLOW_ID));
        assertTrue(builtin.getMessage().contains("内置标准流程不能删除"), builtin.getMessage());

        FlowDefinition active = registry.save(flow(null, "默认候选"), "admin");
        registry.setActiveFlow(active.id());
        PayrollException activeError =
                assertThrows(PayrollException.class, () -> registry.delete(active.id()));
        assertTrue(activeError.getMessage().contains("默认流程不能删除"), activeError.getMessage());

        FlowDefinition spare = registry.save(flow(null, "可删除"), "admin");
        assertTrue(registry.delete(spare.id()));
        assertFalse(registry.delete(spare.id()));
        assertTrue(registry.list().stream().noneMatch(item -> item.id().equals(spare.id())));
    }

    @Test
    @DisplayName("Nacos 读取抖动时沿用上次流程与默认流程，不静默回退")
    void keepsLastKnownGoodWhenReadFails() throws Exception {
        nacos = new FakeNacosServer();
        NacosFlowRegistry seeder = new NacosFlowRegistry(settings(true));
        FlowDefinition saved = seeder.save(flow(null, "情感流程"), "admin");
        seeder.setActiveFlow(saved.id());

        NacosFlowRegistry registry =
                new NacosFlowRegistry(settings(true, Duration.ofMillis(1)));
        assertEquals(2, registry.list().size());
        assertEquals(saved.id(), registry.activeFlowId());

        nacos.failReads = true;
        Thread.sleep(5);

        assertEquals(2, registry.list().size(), "读取失败时应沿用上次成功加载的流程");
        assertEquals(saved.id(), registry.activeFlowId());
        assertNotNull(registry.activeFlow());
    }

    @Test
    @DisplayName("Nacos 写入失败时抛出明确异常")
    void writeFailureThrows() throws IOException {
        nacos = new FakeNacosServer();
        nacos.failWrites = true;
        NacosFlowRegistry registry = registry(true);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> registry.save(flow(null, "新流程"), "admin"));
        assertTrue(error.getMessage().contains("写入 Nacos 配置"), error.getMessage());
    }

    private NacosFlowRegistry registry(boolean enabled) {
        return new NacosFlowRegistry(settings(enabled));
    }

    private NacosSkillSettings settings(boolean enabled) {
        return settings(enabled, Duration.ofSeconds(60));
    }

    private NacosSkillSettings settings(boolean enabled, Duration ttl) {
        return new NacosSkillSettings(
                enabled,
                nacos == null ? "http://127.0.0.1:1" : nacos.url(),
                "payroll-agent",
                "",
                "",
                "",
                Duration.ofSeconds(2),
                ttl);
    }

    private static FlowDefinition flow(String id, String name) {
        return new FlowDefinition(
                id,
                name,
                "测试流程",
                List.of(
                        new FlowNode("start", FlowNode.TYPE_START, "开始", null, null, null,
                                0.0, 0.0),
                        new FlowNode("a", FlowNode.TYPE_AGENT, "计算", RoleSkills.CALCULATOR,
                                null, null, 0.0, 0.0),
                        new FlowNode("end", FlowNode.TYPE_END, "结束", null, null, null,
                                0.0, 0.0)),
                List.of(new FlowEdge("e1", "start", "a", null),
                        new FlowEdge("e2", "a", "end", null)),
                null,
                null,
                null);
    }
}
