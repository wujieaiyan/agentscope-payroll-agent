package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.RebuildRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 知识库入库任务接口：列表、详情、取消、重试与批量重建。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/tasks")
@RequirePermission(Permissions.KB_READ)
public class KbTaskController {

    private final KbTaskService taskService;
    private final AuthService authService;

    public KbTaskController(KbTaskService taskService, AuthService authService) {
        this.taskService = taskService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(
            @PathVariable long seriesId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(taskService.list(seriesId, status, type, limit, user));
    }

    @GetMapping("/{taskId}")
    public ApiResponse detail(
            @PathVariable long seriesId,
            @PathVariable long taskId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(taskService.detail(seriesId, taskId, user));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse cancel(
            @PathVariable long seriesId,
            @PathVariable long taskId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(taskService.cancel(seriesId, taskId, user));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse retry(
            @PathVariable long seriesId,
            @PathVariable long taskId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(taskService.retry(seriesId, taskId, user));
    }

    @PostMapping("/rebuild")
    public ApiResponse rebuild(
            @PathVariable long seriesId,
            @RequestBody RebuildRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        String mode = body == null ? null : body.mode();
        return ApiResponse.ok(
                taskService.rebuild(seriesId, mode, body == null ? null : body.documentIds(), user));
    }
}
