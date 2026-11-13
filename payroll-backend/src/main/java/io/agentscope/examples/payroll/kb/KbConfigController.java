package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.ConfigView;
import io.agentscope.examples.payroll.kb.KbDtos.SaveConfigRequest;
import io.agentscope.examples.payroll.kb.KbDtos.TaskView;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库解析/分块/检索配置版本接口。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}")
@RequirePermission(Permissions.KB_READ)
public class KbConfigController {

    private static final Logger log = LoggerFactory.getLogger(KbConfigController.class);

    private final KbConfigService configService;
    private final KbTaskService taskService;
    private final AuthService authService;

    public KbConfigController(
            KbConfigService configService, KbTaskService taskService, AuthService authService) {
        this.configService = configService;
        this.taskService = taskService;
        this.authService = authService;
    }

    @GetMapping("/config")
    public ApiResponse config(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(configService.active(seriesId, user));
    }

    @GetMapping("/config-versions")
    public ApiResponse versions(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(configService.versions(seriesId, user));
    }

    @GetMapping("/config-versions/{versionId}")
    public ApiResponse version(
            @PathVariable long seriesId,
            @PathVariable long versionId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(configService.version(seriesId, versionId, user));
    }

    @PostMapping("/config-versions")
    public ApiResponse save(
            @PathVariable long seriesId,
            @RequestBody SaveConfigRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        ConfigView config = configService.save(seriesId, body, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config);
        String rebuildMode = body == null ? null : body.rebuildMode();
        if (rebuildMode != null && !rebuildMode.isBlank() && !"NONE".equalsIgnoreCase(rebuildMode)) {
            try {
                List<TaskView> tasks = taskService.rebuild(seriesId, rebuildMode, null, user);
                result.put("rebuildTasks", tasks);
            } catch (RuntimeException e) {
                log.warn("[知识库] 保存配置后重建失败：series={}，mode={}，原因={}",
                        seriesId, rebuildMode, e.getMessage());
                result.put("rebuildError", e.getMessage());
            }
        }
        return ApiResponse.ok(result);
    }
}
