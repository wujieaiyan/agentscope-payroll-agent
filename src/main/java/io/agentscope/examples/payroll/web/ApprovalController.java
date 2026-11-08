package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2ApprovalStore.FlowDraft;
import io.agentscope.examples.payroll.db.H2ApprovalStore.StepDraft;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.ApprovalFlow;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.ApprovalService;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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

/** 审批链配置接口：同一范围最多一条生效流程，部门专属优先于全局。 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private final H2ApprovalStore store;
    private final H2AdminRepository adminRepository;
    private final ApprovalService approvalService;
    private final AuthService authService;
    private final AuditService auditService;

    public ApprovalController(
            H2ApprovalStore store,
            H2AdminRepository adminRepository,
            ApprovalService approvalService,
            AuthService authService,
            AuditService auditService) {
        this.store = store;
        this.adminRepository = adminRepository;
        this.approvalService = approvalService;
        this.authService = authService;
        this.auditService = auditService;
    }

    @GetMapping("/flows")
    @RequirePermission(Permissions.APPROVAL_MANAGE)
    public ApiResponse flows() {
        return ApiResponse.ok(store.flows());
    }

    /** 我的待办：当前节点由当前用户的角色/账号/权限处理的批次。 */
    @GetMapping("/tasks")
    @RequirePermission(Permissions.PAYROLL_APPROVE)
    public ApiResponse tasks(HttpServletRequest request) {
        return ApiResponse.ok(approvalService.tasks(authService.currentUser(request)));
    }

    /** 节点审批人候选项：全部角色与启用用户。 */
    @GetMapping("/approvers")
    @RequirePermission(Permissions.APPROVAL_MANAGE)
    public ApiResponse approvers() {
        List<AppUser> users =
                adminRepository.users().stream().filter(AppUser::active).toList();
        return ApiResponse.ok(Map.of("roles", adminRepository.roles(), "users", users));
    }

    @PostMapping("/flows")
    @RequirePermission(Permissions.APPROVAL_MANAGE)
    public ApiResponse createFlow(@RequestBody(required = false) FlowRequest request) {
        if (request == null) {
            throw new PayrollException("请填写审批链信息");
        }
        ApprovalFlow created = store.createFlow(request.toDraft());
        auditService.record(
                "approval_flow.create",
                "approval_flow",
                String.valueOf(created.flowId()),
                null,
                created);
        return ApiResponse.ok(created);
    }

    @PutMapping("/flows/{flowId}")
    @RequirePermission(Permissions.APPROVAL_MANAGE)
    public ApiResponse updateFlow(
            @PathVariable long flowId, @RequestBody(required = false) FlowRequest request) {
        if (request == null) {
            throw new PayrollException("请填写审批链信息");
        }
        ApprovalFlow before = store.findFlow(flowId).orElse(null);
        ApprovalFlow updated = store.updateFlow(flowId, request.toDraft());
        auditService.record(
                "approval_flow.update",
                "approval_flow",
                String.valueOf(flowId),
                before,
                updated);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/flows/{flowId}")
    @RequirePermission(Permissions.APPROVAL_MANAGE)
    public ApiResponse deleteFlow(@PathVariable long flowId) {
        ApprovalFlow before = store.findFlow(flowId).orElse(null);
        store.deleteFlow(flowId);
        auditService.record(
                "approval_flow.delete",
                "approval_flow",
                String.valueOf(flowId),
                before,
                Map.of("deleted", true));
        return ApiResponse.ok(Map.of("deleted", true));
    }

    /** 审批链表单；启用状态与优先级缺省时按启用、100 处理。 */
    public record FlowRequest(
            String name,
            String scopeType,
            String scopeValue,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer priority,
            Boolean enabled,
            String description,
            List<StepRequest> steps) {

        FlowDraft toDraft() {
            List<StepDraft> drafts =
                    steps == null ? List.of() : steps.stream().map(StepRequest::toDraft).toList();
            return new FlowDraft(
                    name,
                    scopeType,
                    scopeValue,
                    minAmount,
                    maxAmount,
                    priority == null ? 100 : priority,
                    enabled == null || enabled,
                    description,
                    drafts);
        }
    }

    /** 审批节点表单；顺序即执行顺序。 */
    public record StepRequest(
            String name,
            String approverType,
            String approverValue,
            BigDecimal minAmount,
            BigDecimal maxAmount) {

        StepDraft toDraft() {
            return new StepDraft(name, approverType, approverValue, minAmount, maxAmount);
        }
    }
}
