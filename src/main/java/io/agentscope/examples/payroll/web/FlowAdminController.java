package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.FlowValidator;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 智能体流程编排接口：结构校验、全局默认流程与审计。 */
@RestController
@RequestMapping("/api/admin/flows")
public class FlowAdminController {

    private final NacosFlowRegistry flowRegistry;
    private final NacosAgentRegistry agentRegistry;
    private final AuthService authService;
    private final AuditService auditService;

    public FlowAdminController(
            NacosFlowRegistry flowRegistry,
            NacosAgentRegistry agentRegistry,
            AuthService authService,
            AuditService auditService) {
        this.flowRegistry = flowRegistry;
        this.agentRegistry = agentRegistry;
        this.authService = authService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse list() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flows", flowRegistry.list());
        result.put("activeFlowId", flowRegistry.activeFlowId());
        result.put("writable", flowRegistry.writable());
        result.put("location", flowRegistry.location());
        return ApiResponse.ok(result);
    }

    /** 画布节点可选的智能体与存储状态。 */
    @GetMapping("/meta")
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse meta() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writable", flowRegistry.writable());
        result.put("location", flowRegistry.location());
        result.put("agents", agentRegistry.list().stream().map(this::agentOption).toList());
        return ApiResponse.ok(result);
    }

    @PostMapping
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse create(@RequestBody FlowDefinition input, HttpServletRequest request) {
        FlowDefinition normalized = normalize(input, null);
        validate(normalized, false);
        FlowDefinition saved = flowRegistry.save(normalized, operator(request));
        auditService.record("flow.create", "flow_definition", saved.id(), null, saved);
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse update(
            @PathVariable String id,
            @RequestBody FlowDefinition input,
            HttpServletRequest request) {
        FlowDefinition before = find(id);
        FlowDefinition normalized = normalize(input, id);
        validate(normalized, false);
        FlowDefinition saved = flowRegistry.save(normalized, operator(request));
        auditService.record("flow.update", "flow_definition", saved.id(), before, saved);
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse delete(@PathVariable String id) {
        FlowDefinition before = find(id);
        if (!flowRegistry.delete(id)) {
            throw new PayrollException("流程不存在：" + id);
        }
        auditService.record(
                "flow.delete", "flow_definition", id, before, Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    /** 设为全局默认流程；激活前要求所有节点引用的智能体已上架，同时取消默认核算智能体。 */
    @PutMapping("/{id}/active")
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse activate(@PathVariable String id) {
        FlowDefinition flow = find(id);
        validate(flow, true);
        String clearedAgent = agentRegistry.configuredAgentId();
        if (clearedAgent == null) {
            clearedAgent = "";
        }
        if (!clearedAgent.isBlank()) {
            agentRegistry.clearActiveAgent();
        }
        flowRegistry.setActiveFlow(id);
        auditService.record(
                "flow.activate",
                "flow_definition",
                id,
                null,
                Map.of("activeFlowId", id, "clearedAgentId", clearedAgent));
        return ApiResponse.ok(Map.of("activeFlowId", id, "clearedAgentId", clearedAgent));
    }

    /** 取消全局默认流程：下一轮对话回到核算智能体或内置三段流水线。 */
    @DeleteMapping("/active")
    @RequirePermission(Permissions.FLOW_MANAGE)
    public ApiResponse clearActive() {
        String previous = flowRegistry.activeFlowId();
        flowRegistry.clearActiveFlow();
        auditService.record(
                "flow.clear-active",
                "flow_definition",
                previous,
                null,
                Map.of("activeFlowId", ""));
        return ApiResponse.ok(Map.of("activeFlowId", ""));
    }

    private FlowDefinition find(String id) {
        FlowDefinition flow = flowRegistry.find(id);
        if (flow == null) {
            throw new PayrollException("流程不存在：" + id);
        }
        return flow;
    }

    private void validate(FlowDefinition flow, boolean requireOnlineAgents) {
        List<AgentDefinition> agents = agentRegistry.list();
        Map<String, AgentDefinition> byId = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            byId.put(agent.id(), agent);
        }
        List<String> errors = FlowValidator.validate(flow, byId);
        if (!errors.isEmpty()) {
            throw new PayrollException(String.join("；", errors));
        }
        if (requireOnlineAgents) {
            for (FlowNode node : flow.nodes()) {
                if (!FlowNode.TYPE_AGENT.equalsIgnoreCase(node.type())) {
                    continue;
                }
                AgentDefinition agent = byId.get(node.agentId());
                if (agent == null || !AgentDefinition.STATUS_ONLINE.equals(agent.status())) {
                    throw new PayrollException(
                            "节点「" + node.label() + "」引用的智能体未上架：" + node.agentId());
                }
            }
        }
    }

    private FlowDefinition normalize(FlowDefinition input, String forcedId) {
        List<FlowNode> nodes = input.nodes().stream().map(node -> new FlowNode(
                        trim(node.id()),
                        FlowValidator.upper(node.type()),
                        trim(node.name()),
                        trim(node.agentId()),
                        FlowValidator.upper(node.condition()),
                        trim(node.conditionValue()),
                        node.x() == null ? 0.0 : node.x(),
                        node.y() == null ? 0.0 : node.y(),
                        blankToNull(FlowValidator.upper(node.contextMode()))))
                .toList();
        List<FlowEdge> edges = input.edges().stream().map(edge -> new FlowEdge(
                        trim(edge.id()),
                        trim(edge.source()),
                        trim(edge.target()),
                        blankToNull(FlowValidator.upper(edge.sourceHandle()))))
                .toList();
        return new FlowDefinition(
                forcedId,
                trim(input.name()),
                trim(input.description()),
                nodes,
                edges,
                input.createdBy(),
                input.createdAt(),
                input.updatedAt());
    }

    private Map<String, Object> agentOption(AgentDefinition agent) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", agent.id());
        option.put("name", agent.name());
        option.put("avatar", agent.avatar());
        option.put("description", agent.description());
        option.put("status", agent.status());
        return option;
    }

    private String operator(HttpServletRequest request) {
        return authService.currentUser(request).username();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
