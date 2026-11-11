package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.team.TeamAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理台团队页签：只读展示模式配置、近期运行与回退链路。 */
@RestController
@RequestMapping("/api/admin/team")
public class TeamAdminController {

    private final TeamAdminService adminService;

    public TeamAdminController(TeamAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/config")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse config() {
        return ApiResponse.ok(adminService.config());
    }

    @GetMapping("/runs")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse runs(
            @RequestParam(required = false, defaultValue = "0") int days,
            @RequestParam(required = false, defaultValue = "") String mode,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return ApiResponse.ok(adminService.runs(days, mode, status, limit));
    }

    @GetMapping("/runs/{runId}")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse detail(@PathVariable long runId) {
        return ApiResponse.ok(adminService.detail(runId));
    }
}
