package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.CreateDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.DeleteDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.MoveDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.RenameDirectoryRequest;
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

/** 知识库目录树接口。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/directories")
@RequirePermission(Permissions.KB_READ)
public class KbDirectoryController {

    private final KbDirectoryService directoryService;
    private final AuthService authService;

    public KbDirectoryController(KbDirectoryService directoryService, AuthService authService) {
        this.directoryService = directoryService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse tree(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(directoryService.tree(seriesId, user));
    }

    @PostMapping
    public ApiResponse create(
            @PathVariable long seriesId,
            @RequestBody CreateDirectoryRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(directoryService.create(seriesId, body, user));
    }

    @PatchMapping("/{directoryId}")
    public ApiResponse rename(
            @PathVariable long seriesId,
            @PathVariable long directoryId,
            @RequestBody RenameDirectoryRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(directoryService.rename(seriesId, directoryId, body, user));
    }

    @PostMapping("/{directoryId}/move")
    public ApiResponse move(
            @PathVariable long seriesId,
            @PathVariable long directoryId,
            @RequestBody MoveDirectoryRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(directoryService.move(seriesId, directoryId, body, user));
    }

    @DeleteMapping("/{directoryId}")
    public ApiResponse delete(
            @PathVariable long seriesId,
            @PathVariable long directoryId,
            @RequestBody(required = false) DeleteDirectoryRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        directoryService.delete(seriesId, directoryId, body, user);
        return ApiResponse.ok(null);
    }
}
