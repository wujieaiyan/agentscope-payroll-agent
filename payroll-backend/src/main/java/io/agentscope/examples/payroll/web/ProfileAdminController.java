package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.profile.UserProfileAdminService;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 合规删除指定用户的工作偏好；条目与抽取凭证一并物理删除。 */
@RestController
@RequestMapping("/api/admin/profile")
@RequirePermission(Permissions.PROFILE_MANAGE)
public class ProfileAdminController {

    private final UserProfileAdminService service;

    public ProfileAdminController(UserProfileAdminService service) {
        this.service = service;
    }

    @DeleteMapping("/{userId}")
    public ApiResponse purge(@PathVariable long userId) {
        return ApiResponse.ok(Map.of("userId", userId, "deleted", service.purge(userId)));
    }
}
