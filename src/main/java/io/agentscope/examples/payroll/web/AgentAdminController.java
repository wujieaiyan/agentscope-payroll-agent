package io.agentscope.examples.payroll.web;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 专家智能体管理接口：定义存储于 Nacos，写操作需要 agent:manage 权限。 */
@RestController
@RequestMapping("/api/admin/agents")
public class AgentAdminController {

    private static final int MAX_NAME_LENGTH = 30;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_AVATAR_LENGTH = 8;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_LENGTH = 12;

    private final NacosAgentRegistry registry;
    private final AgentSkillRepository skillRepository;
    private final AuthService authService;
    private final AuditService auditService;

    @Autowired
    public AgentAdminController(
            NacosAgentRegistry registry, AuthService authService, AuditService auditService) {
        this(registry, SkillRepositories.create(), authService, auditService);
    }

    AgentAdminController(
            NacosAgentRegistry registry,
            AgentSkillRepository skillRepository,
            AuthService authService,
            AuditService auditService) {
        this.registry = registry;
        this.skillRepository = skillRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    /** 列表支持关键字、标签、技能、状态、可见范围筛选。 */
    @GetMapping
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String visibility) {
        List<AgentDefinition> agents = registry.list();
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            agents = agents.stream()
                    .filter(a -> contains(a.name(), needle) || contains(a.description(), needle))
                    .toList();
        }
        if (tag != null && !tag.isBlank()) {
            String wanted = tag.trim();
            agents = agents.stream().filter(a -> a.tags().contains(wanted)).toList();
        }
        if (skillId != null && !skillId.isBlank()) {
            String wanted = skillId.trim();
            agents = agents.stream().filter(a -> a.skillIds().contains(wanted)).toList();
        }
        if (status != null && !status.isBlank()) {
            agents = agents.stream().filter(a -> a.status().equals(status)).toList();
        }
        if (visibility != null && !visibility.isBlank()) {
            agents = agents.stream().filter(a -> a.visibility().equals(visibility)).toList();
        }
        return ApiResponse.ok(agents);
    }

    /** 存储状态：是否可写（Nacos 启用）、配置位置与当前核算智能体。 */
    @GetMapping("/meta")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("writable", registry.writable());
        meta.put("location", registry.location());
        Set<String> tags = registry.list().stream()
                .flatMap(a -> a.tags().stream())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        meta.put("tags", tags);
        meta.put("activeAgentId", registry.activeAgentId());
        return ApiResponse.ok(meta);
    }

    /** 当前全局核算智能体；配置的智能体不可用时返回实际生效的兜底智能体。 */
    @GetMapping("/active")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse active() {
        AgentDefinition effective = registry.activeAgent();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", effective.id());
        result.put("name", effective.name());
        result.put("configured", registry.activeAgentId());
        return ApiResponse.ok(result);
    }

    /** 管理员指定全局核算智能体，所有会话下一轮对话生效。 */
    @PutMapping("/{id}/active")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse setActive(@PathVariable String id) {
        AgentDefinition before = find(id);
        String agentId = registry.setActiveAgent(id);
        auditService.record("agent.activate", "agent_definition", agentId, null, before);
        return ApiResponse.ok(Map.of("activeAgentId", agentId));
    }

    /** 可绑定技能清单，供编辑表单的技能多选。 */
    @GetMapping("/skills")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse skills() {
        List<Map<String, String>> skills = skillRepository.getAllSkills().stream()
                .map(this::skillOption)
                .toList();
        return ApiResponse.ok(skills);
    }

    @PostMapping
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse create(@RequestBody AgentDefinition input, HttpServletRequest request) {
        AgentDefinition validated = validate(input, null);
        AgentDefinition saved = registry.save(validated, operator(request));
        auditService.record("agent.create", "agent_definition", saved.id(), null, saved);
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse update(
            @PathVariable String id,
            @RequestBody AgentDefinition input,
            HttpServletRequest request) {
        AgentDefinition before = find(id);
        AgentDefinition validated = validate(input, id);
        AgentDefinition saved = registry.save(validated, operator(request));
        auditService.record("agent.update", "agent_definition", saved.id(), before, saved);
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse delete(@PathVariable String id) {
        AgentDefinition before = find(id);
        if (!registry.delete(id)) {
            throw new PayrollException("智能体不存在：" + id);
        }
        auditService.record(
                "agent.delete", "agent_definition", id, before, Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    private AgentDefinition find(String id) {
        return registry.list().stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new PayrollException("智能体不存在：" + id));
    }

    private AgentDefinition validate(AgentDefinition input, String forcedId) {
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new PayrollException("名称必填且不超过 " + MAX_NAME_LENGTH + " 字");
        }
        String description = input.description() == null ? "" : input.description().trim();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new PayrollException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        String avatar =
                input.avatar() == null || input.avatar().isBlank() ? "🤖" : input.avatar().trim();
        if (avatar.length() > MAX_AVATAR_LENGTH) {
            throw new PayrollException("头像最多 " + MAX_AVATAR_LENGTH + " 个字符");
        }
        String visibility = normalizeEnum(
                "可见范围", input.visibility(), AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.VISIBILITY_PUBLIC, AgentDefinition.VISIBILITY_PRIVATE);
        String status = normalizeEnum(
                "状态", input.status(), AgentDefinition.STATUS_OFFLINE,
                AgentDefinition.STATUS_ONLINE, AgentDefinition.STATUS_OFFLINE);
        Set<String> knownSkills = Set.copyOf(skillRepository.getAllSkillNames());
        List<String> skillIds = input.skillIds().stream().distinct().toList();
        for (String skillId : skillIds) {
            if (!knownSkills.contains(skillId)) {
                throw new PayrollException("未知技能：" + skillId);
            }
        }
        List<String> tags = input.tags().stream()
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .distinct()
                .toList();
        if (tags.size() > MAX_TAGS) {
            throw new PayrollException("标签最多 " + MAX_TAGS + " 个");
        }
        for (String tag : tags) {
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new PayrollException("单个标签不能超过 " + MAX_TAG_LENGTH + " 字");
            }
        }
        return new AgentDefinition(
                forcedId,
                name,
                avatar,
                description,
                tags,
                skillIds,
                visibility,
                status,
                input.createdBy(),
                input.createdAt(),
                input.updatedAt());
    }

    private String normalizeEnum(
            String field, String value, String defaultValue, String... allowed) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(upper)) {
                return candidate;
            }
        }
        throw new PayrollException(field + "取值非法：" + value);
    }

    private String operator(HttpServletRequest request) {
        return authService.currentUser(request).username();
    }

    private Map<String, String> skillOption(AgentSkill skill) {
        Map<String, String> option = new LinkedHashMap<>();
        option.put("id", skill.getName());
        option.put("description", skill.getDescription());
        option.put("source", skill.getSource());
        return option;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
