package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import io.agentscope.examples.payroll.skill.RoleSkills;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillAdminControllerTest {

    private final NacosSkillRegistry registry = mock(NacosSkillRegistry.class);
    private final NacosAgentRegistry agentRegistry = mock(NacosAgentRegistry.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SkillAdminController controller =
            new SkillAdminController(registry, agentRegistry, auditService);

    @Test
    @DisplayName("列表返回存储状态并按关键字过滤")
    void listReturnsStorageAndFilters() {
        when(registry.list())
                .thenReturn(
                        List.of(
                                summary(RoleSkills.CALCULATOR, "计算", "builtin", true),
                                summary("payroll-auditor", "薪酬审计", "nacos", false)));
        when(registry.writable()).thenReturn(true);
        when(registry.location()).thenReturn("http://nacos/payroll-agent");

        Map<?, ?> all = (Map<?, ?>) controller.list(null).data();
        assertEquals(true, all.get("writable"));
        assertEquals("http://nacos/payroll-agent", all.get("location"));
        assertEquals(2, ((List<?>) all.get("skills")).size());

        Map<?, ?> filtered = (Map<?, ?>) controller.list("audit").data();
        assertEquals(1, ((List<?>) filtered.get("skills")).size());
    }

    @Test
    @DisplayName("新增返回摘要并写入审计")
    void createWritesAudit() {
        SkillSummary saved = summary("payroll-auditor", "薪酬审计", "nacos", false);
        when(registry.create("payroll-auditor", "薪酬审计", "正文")).thenReturn(saved);

        ApiResponse response =
                controller.create(new SkillAdminController.SkillInput("payroll-auditor", "薪酬审计", "正文"));

        assertSame(saved, response.data());
        verify(auditService).record(eq("skill.create"), eq("skill"), eq("payroll-auditor"), eq(null), any());
    }

    @Test
    @DisplayName("编辑写入前后快照审计")
    void updateWritesAudit() {
        SkillDetail before =
                new SkillDetail(
                        "payroll-auditor", "旧描述", "旧正文", "nacos", false, false, 1L);
        SkillSummary after = summary("payroll-auditor", "新描述", "nacos", false);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.update("payroll-auditor", "新描述", "新正文")).thenReturn(after);

        ApiResponse response =
                controller.update(
                        "payroll-auditor",
                        new SkillAdminController.SkillInput(null, "新描述", "新正文"));

        assertSame(after, response.data());
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", meta(before), meta(after));
    }

    @Test
    @DisplayName("自定义技能被智能体引用时禁止删除")
    void deleteRejectsReferencedCustomSkill() {
        when(registry.detail("payroll-auditor"))
                .thenReturn(
                        new SkillDetail(
                                "payroll-auditor", "薪酬审计", "正文", "nacos", false, false, 1L));
        when(agentRegistry.list()).thenReturn(List.of(agent("个税顾问", "payroll-auditor")));

        PayrollException error =
                assertThrows(PayrollException.class, () -> controller.delete("payroll-auditor"));

        assertTrue(error.getMessage().contains("个税顾问"), error.getMessage());
        verify(registry, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("删除内置覆盖允许，删除后回退内置版本")
    void deleteAllowsBuiltinOverride() {
        when(registry.detail(RoleSkills.CALCULATOR))
                .thenReturn(
                        new SkillDetail(
                                RoleSkills.CALCULATOR,
                                "覆盖版",
                                "正文",
                                "nacos",
                                true,
                                true,
                                1L));
        when(agentRegistry.list())
                .thenReturn(List.of(agent("薪酬计算专家", RoleSkills.CALCULATOR)));
        when(registry.delete(RoleSkills.CALCULATOR)).thenReturn(true);

        ApiResponse response = controller.delete(RoleSkills.CALCULATOR);

        assertEquals(Map.of("deleted", RoleSkills.CALCULATOR), response.data());
        verify(auditService).record(eq("skill.delete"), eq("skill"), eq(RoleSkills.CALCULATOR), any(), any());
    }

    @Test
    @DisplayName("删除未被引用的自定义技能写入审计")
    void deleteWritesAudit() {
        SkillDetail before =
                new SkillDetail("payroll-auditor", "薪酬审计", "正文", "nacos", false, false, 1L);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.delete("payroll-auditor")).thenReturn(true);

        controller.delete("payroll-auditor");

        verify(auditService)
                .record(
                        "skill.delete",
                        "skill",
                        "payroll-auditor",
                        meta(before),
                        Map.of("deleted", "payroll-auditor"));
    }

    @Test
    @DisplayName("详情直接透传技能注册表")
    void detailReturnsRegistryValue() {
        SkillDetail detail =
                new SkillDetail("payroll-auditor", "薪酬审计", "正文", "nacos", false, false, 1L);
        when(registry.detail("payroll-auditor")).thenReturn(detail);

        assertSame(detail, controller.detail("payroll-auditor").data());
    }

    private static AgentDefinition agent(String name, String skillId) {
        return new AgentDefinition(
                "agent-1",
                name,
                "🤖",
                "",
                List.of(),
                List.of(skillId),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "system",
                null,
                null);
    }

    private static SkillSummary summary(String id, String description, String source, boolean builtin) {
        return new SkillSummary(id, description, source, builtin, builtin, 1L, 10);
    }

    private static Map<String, Object> meta(SkillSummary skill) {
        return meta(
                new SkillDetail(
                        skill.id(),
                        skill.description(),
                        "x".repeat(skill.contentLength()),
                        skill.source(),
                        skill.builtin(),
                        skill.overridden(),
                        skill.updatedAt()));
    }

    private static Map<String, Object> meta(SkillDetail skill) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.content().length());
        return meta;
    }
}
