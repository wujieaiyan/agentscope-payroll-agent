package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.support.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 智能体评分榜只读接口；days 为空时使用 payroll.team.stats-window-days。 */
@RestController
@RequestMapping("/api/admin/agent-stats")
public class AgentStatsController {

    private final AgentStatsService statsService;

    public AgentStatsController(AgentStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse stats(@RequestParam(required = false, defaultValue = "0") int days) {
        return ApiResponse.ok(statsService.snapshot(days));
    }
}
