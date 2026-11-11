package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.SaveTagRequest;
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
import org.springframework.web.bind.annotation.RestController;

/** 知识库标签接口。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/tags")
@RequirePermission(Permissions.KB_READ)
public class KbTagController {

    private final KbTagService tagService;
    private final AuthService authService;

    public KbTagController(KbTagService tagService, AuthService authService) {
        this.tagService = tagService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(tagService.list(seriesId, user));
    }

    @PostMapping
    public ApiResponse create(
            @PathVariable long seriesId,
            @RequestBody SaveTagRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(tagService.create(seriesId, body, user));
    }

    @PatchMapping("/{tagId}")
    public ApiResponse update(
            @PathVariable long seriesId,
            @PathVariable long tagId,
            @RequestBody SaveTagRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(tagService.update(seriesId, tagId, body, user));
    }

    @DeleteMapping("/{tagId}")
    public ApiResponse delete(
            @PathVariable long seriesId,
            @PathVariable long tagId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        tagService.delete(seriesId, tagId, user);
        return ApiResponse.ok(null);
    }
}
