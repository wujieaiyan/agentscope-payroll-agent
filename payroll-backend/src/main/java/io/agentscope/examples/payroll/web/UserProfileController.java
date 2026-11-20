package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.profile.UserProfileAdminService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 「我的工作偏好」自助接口；所有条目按当前用户隔离，越权访问一律视为不存在。 */
@RestController
@RequestMapping("/api/profile")
@RequirePermission(Permissions.PROFILE_READ)
public class UserProfileController {

    public record ItemInput(String category, String key, String value) {}

    public record ItemUpdateInput(String value, String status) {}

    private final UserProfileAdminService service;
    private final AuthService authService;

    public UserProfileController(UserProfileAdminService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", service.list(userId(request)));
        data.put("options", service.options(userId(request)));
        return ApiResponse.ok(data);
    }

    @PostMapping("/items")
    @RequirePermission(Permissions.PROFILE_WRITE)
    public ApiResponse create(@RequestBody ItemInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                service.create(
                        userId(request),
                        input == null ? null : input.category(),
                        input == null ? null : input.key(),
                        input == null ? null : input.value()));
    }

    @PutMapping("/items/{id}")
    @RequirePermission(Permissions.PROFILE_WRITE)
    public ApiResponse update(
            @PathVariable long id,
            @RequestBody ItemUpdateInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.update(
                        userId(request),
                        id,
                        input == null ? null : input.value(),
                        input == null ? null : input.status()));
    }

    @DeleteMapping("/items/{id}")
    @RequirePermission(Permissions.PROFILE_WRITE)
    public ApiResponse delete(@PathVariable long id, HttpServletRequest request) {
        service.delete(userId(request), id);
        return ApiResponse.ok(Map.of("deleted", id));
    }

    @DeleteMapping
    @RequirePermission(Permissions.PROFILE_WRITE)
    public ApiResponse clear(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("deleted", service.clear(userId(request))));
    }

    private long userId(HttpServletRequest request) {
        return authService.currentUser(request).id();
    }
}
