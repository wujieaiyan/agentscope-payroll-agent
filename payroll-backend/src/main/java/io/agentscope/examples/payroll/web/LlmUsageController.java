package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.service.LlmUsageService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用量看板只读接口：KPI、趋势、分布与最近调用，时间窗 24h/7d/30d。 */
@RestController
@RequestMapping("/api/admin/usage")
@RequirePermission(Permissions.USAGE_READ)
public class LlmUsageController {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageController.class);

    private final LlmUsageService usageService;
    private final H2AdminRepository adminRepository;

    public LlmUsageController(LlmUsageService usageService, H2AdminRepository adminRepository) {
        this.usageService = usageService;
        this.adminRepository = adminRepository;
    }

    @GetMapping("/overview")
    public ApiResponse overview(
            @RequestParam(required = false, defaultValue = "24h") String range,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String plane,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String user) {
        LlmUsageQuery query =
                LlmUsageQuery.range(range, LocalDateTime.now(), model, plane, agent, user);
        return ApiResponse.ok(usageService.overview(query, userNames()));
    }

    /** 用户展示名只用于看板标签，查询失败时退回用户 id，不影响用量数据。 */
    private Map<String, String> userNames() {
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (AppUser user : adminRepository.users()) {
                String label =
                        user.displayName() == null || user.displayName().isBlank()
                                ? user.username()
                                : user.displayName();
                names.put(String.valueOf(user.id()), label);
            }
            return names;
        } catch (RuntimeException e) {
            log.warn("[用量看板] 读取用户展示名失败：原因={}", e.getMessage());
            return Map.of();
        }
    }
}
