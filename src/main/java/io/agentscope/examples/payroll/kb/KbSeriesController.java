package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.CreateSeriesRequest;
import io.agentscope.examples.payroll.kb.KbDtos.UpdateSeriesRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 知识库系列管理接口；库内写操作在服务层按库级角色二次校验。 */
@RestController
@RequestMapping("/api/kb/series")
@RequirePermission(Permissions.KB_READ)
public class KbSeriesController {

    private final KbSeriesService seriesService;
    private final AuthService authService;

    public KbSeriesController(KbSeriesService seriesService, AuthService authService) {
        this.seriesService = seriesService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "dept_id", required = false) Long deptId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.list(user, scope, keyword, deptId, status, page, size));
    }

    @PostMapping
    @RequirePermission(Permissions.KB_CREATE)
    public ApiResponse create(
            @RequestBody CreateSeriesRequest body, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.create(body, user));
    }

    @GetMapping("/{seriesId}")
    public ApiResponse get(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.get(seriesId, user));
    }

    @PatchMapping("/{seriesId}")
    public ApiResponse update(
            @PathVariable long seriesId,
            @RequestBody UpdateSeriesRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.update(seriesId, body, user));
    }

    @PostMapping("/{seriesId}/disable")
    public ApiResponse disable(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.setStatus(seriesId, false, user));
    }

    @PostMapping("/{seriesId}/enable")
    public ApiResponse enable(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.setStatus(seriesId, true, user));
    }

    @DeleteMapping("/{seriesId}")
    public ApiResponse delete(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        seriesService.delete(seriesId, user);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{seriesId}/stats")
    public ApiResponse stats(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(seriesService.stats(seriesId, user));
    }
}
