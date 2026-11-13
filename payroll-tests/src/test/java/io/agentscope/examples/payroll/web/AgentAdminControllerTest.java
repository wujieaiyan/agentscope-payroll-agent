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

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.RoleSkills;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentAdminControllerTest {

    private final NacosAgentRegistry registry = mock(NacosAgentRegistry.class);
    private final AgentSkillRepository skillRepository = mock(AgentSkillRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final AgentAdminController controller =
            new AgentAdminController(registry, skillRepository, authService, auditService);

    @BeforeEach
    void setUp() {
        when(skillRepository.getAllSkillNames()).thenReturn(RoleSkills.ids());
        when(skillRepository.getAllSkills())
                .thenReturn(RoleSkills.ids().stream().map(RoleSkills::skill).toList());
        when(authService.currentUser(request)).thenReturn(AuthUser.system());
    }

    @Test
    @DisplayName("新建成功写入审计，前后快照完整")
    void createWritesAudit() {
        AgentDefinition saved = agent("agent-1", "个税顾问", AgentDefinition.STATUS_ONLINE);
        when(registry.save(any(), eq("system"))).thenReturn(saved);

        ApiResponse response = controller.create(agent(null, "个税顾问", null), request);

        assertSame(saved, response.data());
        verify(auditService)
                .record("agent.create", "agent_definition", "agent-1", null, saved);
    }

    @Test
    @DisplayName("新建时归一化空白与重复字段")
    void createNormalizesInput() {
        when(registry.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentDefinition input =
                new AgentDefinition(
                        null,
                        "  个税顾问  ",
                        " ",
                        "  负责个税  ",
                        List.of(" 税务 ", "税务", " "),
                        List.of(RoleSkills.CALCULATOR, RoleSkills.CALCULATOR),
                        null,
                        null,
                        null,
                        null,
                        null);

        controller.create(input, request);

        ArgumentCaptor<AgentDefinition> captor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(registry).save(captor.capture(), eq("system"));
        AgentDefinition saved = captor.getValue();
        assertEquals("个税顾问", saved.name());
        assertEquals("🤖", saved.avatar());
        assertEquals("负责个税", saved.description());
        assertEquals(List.of("税务"), saved.tags());
        assertEquals(List.of(RoleSkills.CALCULATOR), saved.skillIds());
        assertEquals(AgentDefinition.VISIBILITY_PUBLIC, saved.visibility());
        assertEquals(AgentDefinition.STATUS_OFFLINE, saved.status());
    }

    @Test
    @DisplayName("未知技能、非法枚举与超长字段被拒绝，且不落库")
    void rejectsInvalidInput() {
        AgentDefinition blankName =
                new AgentDefinition(
                        null, "  ", "🤖", "", List.of(), List.of(),
                        "PUBLIC", "ONLINE", null, null, null);
        PayrollException nameError =
                assertThrows(PayrollException.class, () -> controller.create(blankName, request));
        assertTrue(nameError.getMessage().contains("名称必填"), nameError.getMessage());

        AgentDefinition unknownSkill =
                new AgentDefinition(
                        null, "顾问", "🤖", "", List.of(), List.of("ghost-skill"),
                        "PUBLIC", "ONLINE", null, null, null);
        PayrollException skillError =
                assertThrows(PayrollException.class, () -> controller.create(unknownSkill, request));
        assertTrue(skillError.getMessage().contains("未知技能"), skillError.getMessage());

        AgentDefinition badStatus = agent(null, "顾问", "UNKNOWN");
        PayrollException statusError =
                assertThrows(PayrollException.class, () -> controller.create(badStatus, request));
        assertTrue(statusError.getMessage().contains("状态取值非法"), statusError.getMessage());

        AgentDefinition badVisibility =
                new AgentDefinition(
                        null, "顾问", "🤖", "", List.of(), List.of(),
                        "TEAM", "ONLINE", null, null, null);
        PayrollException visibilityError =
                assertThrows(
                        PayrollException.class,
                        () -> controller.create(badVisibility, request));
        assertTrue(
                visibilityError.getMessage().contains("可见范围取值非法"),
                visibilityError.getMessage());

        AgentDefinition longDescription =
                new AgentDefinition(
                        null, "顾问", "🤖", "x".repeat(201), List.of(), List.of(),
                        "PUBLIC", "ONLINE", null, null, null);
        assertThrows(PayrollException.class, () -> controller.create(longDescription, request));

        AgentDefinition tooManyTags =
                new AgentDefinition(
                        null,
                        "顾问",
                        "🤖",
                        "",
                        IntStream.range(0, 7).mapToObj(i -> "标签" + i).toList(),
                        List.of(),
                        "PUBLIC",
                        "ONLINE",
                        null,
                        null,
                        null);
        assertThrows(PayrollException.class, () -> controller.create(tooManyTags, request));

        AgentDefinition longAvatar =
                new AgentDefinition(
                        null, "顾问", "x".repeat(41), "", List.of(), List.of(),
                        "PUBLIC", "ONLINE", null, null, null);
        assertThrows(PayrollException.class, () -> controller.create(longAvatar, request));

        verify(registry, never()).save(any(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("列表支持关键字、标签、技能、状态与范围筛选")
    void listFiltersAgents() {
        AgentDefinition calculator =
                new AgentDefinition(
                        "calc", "薪酬计算", "🧮", "核算工资", List.of("薪酬"),
                        List.of(RoleSkills.CALCULATOR), "PUBLIC", "ONLINE", "system", null, null);
        AgentDefinition advisor =
                new AgentDefinition(
                        "tax", "个税顾问", "🧾", "税务分析", List.of("税务"),
                        List.of(RoleSkills.VALIDATOR), "PRIVATE", "OFFLINE", "system", null, null);
        when(registry.list()).thenReturn(List.of(calculator, advisor));

        assertEquals(1, ids(controller.list("个税", null, null, null, null)).size());
        assertEquals(1, ids(controller.list(null, "税务", null, null, null)).size());
        assertEquals(
                1,
                ids(controller.list(null, null, RoleSkills.CALCULATOR, null, null)).size());
        assertEquals(1, ids(controller.list(null, null, null, "OFFLINE", null)).size());
        assertEquals(
                1,
                ids(controller.list(null, null, null, null, AgentDefinition.VISIBILITY_PRIVATE))
                        .size());
        assertEquals(2, ids(controller.list(null, null, null, null, null)).size());
    }

    @Test
    @DisplayName("更新不存在智能体直接报错")
    void updateMissingAgentThrows() {
        when(registry.list()).thenReturn(List.of());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> controller.update("missing", agent(null, "顾问", null), request));

        assertTrue(error.getMessage().contains("智能体不存在"), error.getMessage());
        verify(registry, never()).save(any(), any());
    }

    @Test
    @DisplayName("更新写入前后快照审计")
    void updateWritesAudit() {
        AgentDefinition before = agent("agent-1", "旧名称", AgentDefinition.STATUS_ONLINE);
        AgentDefinition after = agent("agent-1", "新名称", AgentDefinition.STATUS_OFFLINE);
        when(registry.list()).thenReturn(List.of(before));
        when(registry.save(any(), eq("system"))).thenReturn(after);

        ApiResponse response =
                controller.update("agent-1", agent(null, "新名称", AgentDefinition.STATUS_OFFLINE), request);

        assertSame(after, response.data());
        verify(auditService)
                .record("agent.update", "agent_definition", "agent-1", before, after);
    }

    @Test
    @DisplayName("删除写入审计，重复删除报错")
    void deleteWritesAudit() {
        AgentDefinition before = agent("agent-1", "个税顾问", AgentDefinition.STATUS_ONLINE);
        when(registry.list()).thenReturn(List.of(before));
        when(registry.delete("agent-1")).thenReturn(true);

        controller.delete("agent-1");

        verify(auditService)
                .record(
                        "agent.delete",
                        "agent_definition",
                        "agent-1",
                        before,
                        Map.of("deleted", "agent-1"));

        when(registry.delete("agent-1")).thenReturn(false);
        assertThrows(PayrollException.class, () -> controller.delete("agent-1"));
    }

    @Test
    @DisplayName("meta 返回存储可写性、位置、标签集合与核算智能体")
    void metaReportsStorage() {
        AgentDefinition agent = agent("agent-1", "个税顾问", AgentDefinition.STATUS_ONLINE);
        when(registry.writable()).thenReturn(true);
        when(registry.location()).thenReturn("http://nacos/payroll-agent");
        when(registry.activeAgentId()).thenReturn("agent-1");
        when(registry.list()).thenReturn(List.of(agent));

        Map<?, ?> meta = (Map<?, ?>) controller.meta().data();

        assertEquals(true, meta.get("writable"));
        assertEquals("http://nacos/payroll-agent", meta.get("location"));
        assertEquals("agent-1", meta.get("activeAgentId"));
        assertTrue(meta.get("tags").toString().contains("税务"), meta.toString());
    }

    @Test
    @DisplayName("设置为核算智能体写入审计并返回被取消的默认流程")
    void setActiveWritesAudit() {
        AgentDefinition before = agent("agent-1", "个税顾问", AgentDefinition.STATUS_ONLINE);
        when(registry.list()).thenReturn(List.of(before));
        when(registry.setActiveAgent("agent-1")).thenReturn("agent-1");

        ApiResponse response = controller.setActive("agent-1");

        Map<?, ?> data = (Map<?, ?>) response.data();
        assertEquals("agent-1", data.get("activeAgentId"));
        assertEquals("", data.get("clearedFlowId"));
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService)
                .record(
                        eq("agent.activate"),
                        eq("agent_definition"),
                        eq("agent-1"),
                        eq(null),
                        audit.capture());
        assertEquals("agent-1", audit.getValue().get("activeAgentId"));
    }

    @Test
    @DisplayName("active 返回实际生效、配置与可用状态")
    void activeReportsEffectiveAgent() {
        AgentDefinition effective = agent("agent-1", "个税顾问", AgentDefinition.STATUS_ONLINE);
        when(registry.activeAgent()).thenReturn(effective);
        when(registry.configuredAgentId()).thenReturn("configured-9");

        Map<?, ?> data = (Map<?, ?>) controller.active().data();

        assertEquals("agent-1", data.get("agentId"));
        assertEquals("个税顾问", data.get("name"));
        assertEquals("configured-9", data.get("configured"));
        assertEquals(false, data.get("available"));
    }

    @Test
    @DisplayName("取消核算智能体写入审计")
    void clearActiveWritesAudit() {
        when(registry.configuredAgentId()).thenReturn("agent-1");

        ApiResponse response = controller.clearActive();

        assertEquals(Map.of("activeAgentId", ""), response.data());
        verify(registry).clearActiveAgent();
        verify(auditService)
                .record(
                        eq("agent.clear-active"),
                        eq("agent_definition"),
                        eq("agent-1"),
                        eq(null),
                        any());
    }

    private static List<AgentDefinition> ids(ApiResponse response) {
        @SuppressWarnings("unchecked")
        List<AgentDefinition> agents = (List<AgentDefinition>) response.data();
        return agents;
    }

    private static AgentDefinition agent(String id, String name, String status) {
        return new AgentDefinition(
                id,
                name,
                "🧾",
                "负责个税测算",
                List.of("税务"),
                List.of(RoleSkills.CALCULATOR),
                AgentDefinition.VISIBILITY_PUBLIC,
                status,
                "system",
                LocalDateTime.of(2026, 11, 1, 9, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
    }
}
