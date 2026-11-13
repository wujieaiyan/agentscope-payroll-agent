package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.GrantMembersRequest;
import io.agentscope.examples.payroll.kb.KbDtos.UpdateMemberRoleRequest;
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

/** 知识库成员管理接口。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/members")
@RequirePermission(Permissions.KB_READ)
public class KbMemberController {

    private final KbMemberService memberService;
    private final AuthService authService;

    public KbMemberController(KbMemberService memberService, AuthService authService) {
        this.memberService = memberService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(memberService.list(seriesId, user));
    }

    @GetMapping("/candidates")
    public ApiResponse candidates(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(memberService.candidates(seriesId, user));
    }

    @PostMapping
    public ApiResponse grant(
            @PathVariable long seriesId,
            @RequestBody GrantMembersRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(memberService.grant(seriesId, body, user));
    }

    @PatchMapping("/{memberId}")
    public ApiResponse updateRole(
            @PathVariable long seriesId,
            @PathVariable long memberId,
            @RequestBody UpdateMemberRoleRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(memberService.updateRole(seriesId, memberId, body.role(), user));
    }

    @DeleteMapping("/{memberId}")
    public ApiResponse remove(
            @PathVariable long seriesId,
            @PathVariable long memberId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        memberService.remove(seriesId, memberId, user);
        return ApiResponse.ok(null);
    }
}
