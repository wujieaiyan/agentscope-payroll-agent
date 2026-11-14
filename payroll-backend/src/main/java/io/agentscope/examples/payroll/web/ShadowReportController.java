package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.team.ShadowReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 影子模式对比报告；days 为空时使用 payroll.team.stats-window-days。 */
@RestController
@RequestMapping("/api/admin/team/shadow-report")
public class ShadowReportController {

    private final ShadowReportService reportService;

    public ShadowReportController(ShadowReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse report(@RequestParam(required = false, defaultValue = "0") int days) {
        return ApiResponse.ok(reportService.report(days));
    }
}
