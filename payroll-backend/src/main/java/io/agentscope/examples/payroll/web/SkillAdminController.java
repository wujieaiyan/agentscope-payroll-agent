package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 技能管理接口：直接读写 Nacos 配置 {@code <skill-id>.md}，写操作需要 skill:manage 权限。 */
@RestController
@RequestMapping("/api/admin/skills")
public class SkillAdminController {

    private final NacosSkillRegistry registry;
    private final NacosAgentRegistry agentRegistry;
    private final AuditService auditService;

    public SkillAdminController(
            NacosSkillRegistry registry,
            NacosAgentRegistry agentRegistry,
            AuditService auditService) {
        this.registry = registry;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
    }

    /** 列表同时返回存储状态，Nacos 未启用时前端按只读展示内置技能。 */
    @GetMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse list(@RequestParam(required = false) String keyword) {
        List<SkillSummary> skills = registry.list();
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            skills = skills.stream()
                    .filter(skill -> contains(skill.id(), needle)
                            || contains(skill.description(), needle))
                    .toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writable", registry.writable());
        result.put("location", registry.location());
        result.put("skills", skills);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse detail(@PathVariable String id) {
        return ApiResponse.ok(registry.detail(id));
    }

    @PostMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse create(@RequestBody SkillInput input) {
        SkillSummary saved = registry.create(input.id(), input.description(), input.content());
        auditService.record("skill.create", "skill", saved.id(), null, meta(saved));
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse update(@PathVariable String id, @RequestBody SkillInput input) {
        SkillDetail before = registry.detail(id);
        SkillSummary saved = registry.update(id, input.description(), input.content());
        auditService.record("skill.update", "skill", saved.id(), meta(before), meta(saved));
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse delete(@PathVariable String id) {
        SkillDetail before = registry.detail(id);
        if (!before.builtin()) {
            List<String> users = agentRegistry.list().stream()
                    .filter(agent -> agent.skillIds() != null && agent.skillIds().contains(id))
                    .map(AgentDefinition::name)
                    .toList();
            if (!users.isEmpty()) {
                throw new PayrollException(
                        "技能正在被智能体使用：" + String.join("、", users) + "，请先解绑");
            }
        }
        registry.delete(id);
        auditService.record(
                "skill.delete", "skill", id, meta(before), Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    private static Map<String, Object> meta(SkillSummary skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.contentLength());
        return meta;
    }

    private static Map<String, Object> meta(SkillDetail skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.content().length());
        return meta;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 新增与编辑共用请求体；编辑时路径 id 优先。 */
    public record SkillInput(String id, String description, String content) {
    }
}
