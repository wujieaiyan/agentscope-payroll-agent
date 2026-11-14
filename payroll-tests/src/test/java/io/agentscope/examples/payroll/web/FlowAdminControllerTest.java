package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.RoleSkills;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlowAdminControllerTest {

    private final NacosFlowRegistry flowRegistry = mock(NacosFlowRegistry.class);
    private final NacosAgentRegistry agentRegistry = mock(NacosAgentRegistry.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final FlowAdminController controller =
            new FlowAdminController(flowRegistry, agentRegistry, authService, auditService);

    @BeforeEach
    void setUp() {
        when(authService.currentUser(request)).thenReturn(AuthUser.system());
        when(agentRegistry.list())
                .thenReturn(
                        List.of(
                                agent(RoleSkills.CALCULATOR, AgentDefinition.STATUS_ONLINE),
                                agent(RoleSkills.VALIDATOR, AgentDefinition.STATUS_ONLINE)));
    }

    @Test
    @DisplayName("新建流程归一化输入、校验结构并写入审计")
    void createNormalizesAndAudits() {
        FlowDefinition input =
                new FlowDefinition(
                        "client-id",
                        "  我的流程  ",
                        "  说明  ",
                        List.of(
                                new FlowNode(" start ", "start", " 开始 ", null, null, null,
                                        null, null),
                                new FlowNode("a", "agent", " 计算 ", "  " + RoleSkills.CALCULATOR + " ",
                                        null, null, 10.0, null),
                                new FlowNode("end", "end", "结束", null, null, null, 99.0, 20.0)),
                        List.of(
                                new FlowEdge("e1", " start ", "a", ""),
                                new FlowEdge("e2", "a", " end ", null)),
                        "ignored",
                        LocalDateTime.now(),
                        LocalDateTime.now());
        when(flowRegistry.save(any(), eq("system")))
                .thenAnswer(invocation -> withId("flow-1", invocation.getArgument(0)));

        ApiResponse response = controller.create(input, request);

        assertEquals("flow-1", ((FlowDefinition) response.data()).id());
        ArgumentCaptor<FlowDefinition> captor = ArgumentCaptor.forClass(FlowDefinition.class);
        verify(flowRegistry).save(captor.capture(), eq("system"));
        FlowDefinition saved = captor.getValue();
        assertNull(saved.id());
        assertEquals("我的流程", saved.name());
        assertEquals("说明", saved.description());
        assertEquals("START", saved.nodes().get(0).type());
        assertEquals("开始", saved.nodes().get(0).name());
        assertEquals(0.0, saved.nodes().get(0).x(), 0.0001);
        assertEquals(RoleSkills.CALCULATOR, saved.nodes().get(1).agentId());
        assertEquals(0.0, saved.nodes().get(1).y(), 0.0001);
        assertNull(saved.edges().get(0).sourceHandle());
        verify(auditService)
                .record("flow.create", "flow_definition", "flow-1", null, response.data());
    }

    @Test
    @DisplayName("非法流程在落库前被拒绝")
    void rejectsInvalidFlow() {
        FlowDefinition twoStarts =
                flow(
                        "flow-1",
                        List.of(
                                node("start", FlowNode.TYPE_START, null),
                                node("start-2", FlowNode.TYPE_START, null),
                                node("a", FlowNode.TYPE_AGENT, RoleSkills.CALCULATOR),
                                node("end", FlowNode.TYPE_END, null)),
                        List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "start-2", "a"),
                                edge("e3", "a", "end")));

        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> controller.create(twoStarts, request));
        assertTrue(error.getMessage().contains("恰好包含一个开始节点"), error.getMessage());
        verify(flowRegistry, never()).save(any(), any());

        FlowDefinition ghostAgent =
                flow(
                        "flow-1",
                        List.of(
                                node("start", FlowNode.TYPE_START, null),
                                node("a", FlowNode.TYPE_AGENT, "ghost-agent"),
                                node("end", FlowNode.TYPE_END, null)),
                        List.of(edge("e1", "start", "a"), edge("e2", "a", "end")));
        PayrollException agentError =
                assertThrows(
                        PayrollException.class, () -> controller.create(ghostAgent, request));
        assertTrue(agentError.getMessage().contains("不存在"), agentError.getMessage());
    }

    @Test
    @DisplayName("更新使用路径 id，并记录修改前后快照")
    void updateUsesPathId() {
        FlowDefinition before = flow("flow-1", standardNodes(), standardEdges());
        when(flowRegistry.find("flow-1")).thenReturn(before);
        FlowDefinition input = flow("client-id", "新名称", standardNodes(), standardEdges());
        when(flowRegistry.save(any(), eq("system")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FlowDefinition saved = (FlowDefinition) controller.update("flow-1", input, request).data();

        assertEquals("flow-1", saved.id());
        assertEquals("新名称", saved.name());
        verify(auditService)
                .record("flow.update", "flow_definition", "flow-1", before, saved);
    }

    @Test
    @DisplayName("不存在或不可删除的流程返回明确错误")
    void deleteRejectsMissingAndBuiltin() {
        when(flowRegistry.find("ghost")).thenReturn(null);
        assertThrows(PayrollException.class, () -> controller.delete("ghost"));

        FlowDefinition builtin = flow("flow-1", standardNodes(), standardEdges());
        when(flowRegistry.find("flow-1")).thenReturn(builtin);
        when(flowRegistry.delete("flow-1"))
                .thenThrow(new PayrollException("内置标准流程不能删除"));

        PayrollException error =
                assertThrows(PayrollException.class, () -> controller.delete("flow-1"));
        assertTrue(error.getMessage().contains("不能删除"), error.getMessage());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("删除成功写入审计")
    void deleteWritesAudit() {
        FlowDefinition before = flow("flow-1", standardNodes(), standardEdges());
        when(flowRegistry.find("flow-1")).thenReturn(before);
        when(flowRegistry.delete("flow-1")).thenReturn(true);

        ApiResponse response = controller.delete("flow-1");

        assertEquals(Map.of("deleted", "flow-1"), response.data());
        verify(auditService)
                .record(
                        eq("flow.delete"),
                        eq("flow_definition"),
                        eq("flow-1"),
                        eq(before),
                        eq(Map.of("deleted", "flow-1")));
    }

    @Test
    @DisplayName("激活前检查引用智能体已上架")
    void activateRequiresOnlineAgents() {
        FlowDefinition online = flow("flow-1", standardNodes(), standardEdges());
        when(flowRegistry.find("flow-1")).thenReturn(online);
        when(agentRegistry.configuredAgentId()).thenReturn("agent-x");

        ApiResponse response = controller.activate("flow-1");

        assertEquals(
                Map.of("activeFlowId", "flow-1", "clearedAgentId", "agent-x"),
                response.data());
        verify(flowRegistry).setActiveFlow("flow-1");
        verify(agentRegistry).clearActiveAgent();
        verify(auditService)
                .record(
                        eq("flow.activate"),
                        eq("flow_definition"),
                        eq("flow-1"),
                        eq(null),
                        eq(Map.of("activeFlowId", "flow-1", "clearedAgentId", "agent-x")));

        when(agentRegistry.list())
                .thenReturn(
                        List.of(agent(RoleSkills.CALCULATOR, AgentDefinition.STATUS_OFFLINE),
                                agent(RoleSkills.VALIDATOR, AgentDefinition.STATUS_ONLINE)));
        PayrollException error =
                assertThrows(PayrollException.class, () -> controller.activate("flow-1"));
        assertTrue(error.getMessage().contains("未上架"), error.getMessage());
    }

    @Test
    @DisplayName("列表与画布元数据返回存储状态和智能体选项")
    void listAndMeta() {
        FlowDefinition flow = flow("flow-1", standardNodes(), standardEdges());
        when(flowRegistry.list()).thenReturn(List.of(flow));
        when(flowRegistry.activeFlowId()).thenReturn("flow-1");
        when(flowRegistry.writable()).thenReturn(true);
        when(flowRegistry.location()).thenReturn("http://nacos/payroll-agent");

        Map<String, Object> list = (Map<String, Object>) controller.list().data();
        assertEquals(List.of(flow), list.get("flows"));
        assertEquals("flow-1", list.get("activeFlowId"));
        assertEquals(Boolean.TRUE, list.get("writable"));
        assertEquals("http://nacos/payroll-agent", list.get("location"));

        Map<String, Object> meta = (Map<String, Object>) controller.meta().data();
        assertEquals(Boolean.TRUE, meta.get("writable"));
        List<?> agents = (List<?>) meta.get("agents");
        assertEquals(2, agents.size());
        Map<?, ?> first = (Map<?, ?>) agents.get(0);
        assertEquals(RoleSkills.CALCULATOR, first.get("id"));
        assertEquals(AgentDefinition.STATUS_ONLINE, first.get("status"));
    }

    private static FlowDefinition withId(String id, FlowDefinition flow) {
        return new FlowDefinition(
                id,
                flow.name(),
                flow.description(),
                flow.nodes(),
                flow.edges(),
                "system",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private static AgentDefinition agent(String id, String status) {
        return new AgentDefinition(
                id,
                id,
                "🤖",
                "",
                List.of(),
                List.of(id),
                AgentDefinition.VISIBILITY_PUBLIC,
                status,
                null,
                null,
                null);
    }

    private static FlowDefinition flow(
            String id, List<FlowNode> nodes, List<FlowEdge> edges) {
        return flow(id, "测试流程", nodes, edges);
    }

    private static FlowDefinition flow(
            String id, String name, List<FlowNode> nodes, List<FlowEdge> edges) {
        LocalDateTime now = LocalDateTime.now();
        return new FlowDefinition(id, name, "", nodes, edges, "admin", now, now);
    }

    private static List<FlowNode> standardNodes() {
        return List.of(
                node("start", FlowNode.TYPE_START, null),
                node("a", FlowNode.TYPE_AGENT, RoleSkills.CALCULATOR),
                node("end", FlowNode.TYPE_END, null));
    }

    private static List<FlowEdge> standardEdges() {
        return List.of(edge("e1", "start", "a"), edge("e2", "a", "end"));
    }

    private static FlowNode node(String id, String type, String agentId) {
        return new FlowNode(id, type, id, agentId, null, null, 0.0, 0.0);
    }

    private static FlowEdge edge(String id, String source, String target) {
        return new FlowEdge(id, source, target, null);
    }
}
