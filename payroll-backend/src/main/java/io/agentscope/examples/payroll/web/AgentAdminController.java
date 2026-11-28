package io.agentscope.examples.payroll.web;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.examples.payroll.agent.AgentPublishGate;
import io.agentscope.examples.payroll.agent.BuiltinToolCatalog;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.model.AgentModelSettings;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.skill.ModelConnectionRegistry;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.PayrollSkillCatalog;
import io.agentscope.examples.payroll.skill.SkillManifest;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int MAX_AVATAR_LENGTH = 40;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_LENGTH = 12;
    private static final int MAX_CAPABILITIES = 8;
    private static final int MAX_CAPABILITY_LENGTH = 24;
    private static final java.util.regex.Pattern CAPABILITY_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9:_-]+");

    private final NacosAgentRegistry registry;
    private final AgentSkillRepository skillRepository;
    private final NacosFlowRegistry flowRegistry;
    private final AuthService authService;
    private final AuditService auditService;
    private final ModelConnectionRegistry modelRegistry;

    /** Nacos 未配置 actionConfirm 时展示的环境默认值（与 runtime 的同名配置保持一致）。 */
    @Value("${payroll.chat.action-confirm.enabled:false}")
    private boolean actionConfirmFallback = false;

    @Autowired
    public AgentAdminController(
            NacosAgentRegistry registry,
            NacosFlowRegistry flowRegistry,
            AuthService authService,
            AuditService auditService,
            ModelConnectionRegistry modelRegistry,
            ObjectProvider<AgentSkillRepository> skillRepositories) {
        this(
                registry,
                // db 源的仓库是容器 bean（复用 Spring 连接池）；nacos 源没有这个 bean，退回进程内共享槽位。
                // 直接调 SkillRepositories.create() 会让 db 源多开一个 Hikari 池。
                skillRepositories.getIfAvailable(SkillRepositories::shared),
                flowRegistry,
                authService,
                auditService,
                modelRegistry);
    }

    AgentAdminController(
            NacosAgentRegistry registry,
            AgentSkillRepository skillRepository,
            AuthService authService,
            AuditService auditService) {
        this(
                registry,
                skillRepository,
                null,
                authService,
                auditService,
                ModelConnectionRegistry.shared());
    }

    AgentAdminController(
            NacosAgentRegistry registry,
            AgentSkillRepository skillRepository,
            NacosFlowRegistry flowRegistry,
            AuthService authService,
            AuditService auditService) {
        this(
                registry,
                skillRepository,
                flowRegistry,
                authService,
                auditService,
                ModelConnectionRegistry.shared());
    }

    AgentAdminController(
            NacosAgentRegistry registry,
            AgentSkillRepository skillRepository,
            NacosFlowRegistry flowRegistry,
            AuthService authService,
            AuditService auditService,
            ModelConnectionRegistry modelRegistry) {
        this.registry = registry;
        this.skillRepository = skillRepository;
        this.flowRegistry = flowRegistry;
        this.authService = authService;
        this.auditService = auditService;
        this.modelRegistry = modelRegistry;
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", agents);
        result.put("revision", registry.revision());
        result.put("settingsRevision", registry.settingsRevision());
        return ApiResponse.ok(result);
    }

    /** 存储状态：是否可写（Nacos 启用）、配置位置与当前核算智能体。 */
    @GetMapping("/meta")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("writable", registry.writable());
        meta.put("location", registry.location());
        meta.put("revision", registry.revision());
        meta.put("settingsRevision", registry.settingsRevision());
        Set<String> tags = registry.list().stream()
                .flatMap(a -> a.tags().stream())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        meta.put("tags", tags);
        meta.put("activeAgentId", registry.activeAgentId());
        meta.put("tools", BuiltinToolCatalog.TOOL_NAMES.stream().sorted().toList());
        return ApiResponse.ok(meta);
    }

    /** 当前全局核算智能体；配置的智能体不可用时标记 available=false，不参与对话路由。 */
    @GetMapping("/active")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse active() {
        String configured = registry.configuredAgentId();
        AgentDefinition effective = registry.activeAgent();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", effective.id());
        result.put("name", effective.name());
        result.put("configured", configured);
        result.put(
                "available",
                configured.isBlank() || effective.id().equals(configured));
        return ApiResponse.ok(result);
    }

    /** 管理员指定全局核算智能体；单份运行时设置文档内同时清空默认流程。 */
    @PutMapping("/{id}/active")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse setActive(
            @PathVariable String id,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        find(id);
        String previousFlow = flowRegistry == null ? "" : flowRegistry.activeFlowId();
        String agentId = registry.setActiveAgent(id, revision);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("activeAgentId", agentId);
        audit.put("clearedFlowId", previousFlow);
        auditService.record("agent.activate", "agent_definition", agentId, null, audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeAgentId", agentId);
        result.put("clearedFlowId", previousFlow);
        return ApiResponse.ok(result);
    }

    /** 取消全局核算智能体：对话回到内置三段流水线。 */
    @DeleteMapping("/active")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse clearActive(
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        String previous = registry.configuredAgentId();
        registry.clearActiveAgent(revision);
        auditService.record(
                "agent.clear-active",
                "agent_definition",
                previous,
                null,
                Map.of("activeAgentId", ""));
        return ApiResponse.ok(Map.of("activeAgentId", ""));
    }

    /** 写操作确认开关：Nacos 覆盖值优先，未配置时展示运行期环境默认值。 */
    @GetMapping("/action-confirm")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse actionConfirm() {
        Optional<Boolean> override = registry.actionConfirmOverride();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", override.orElse(actionConfirmFallback));
        result.put("source", override.isPresent() ? "NACOS" : "ENV");
        result.put("writable", registry.writable());
        return ApiResponse.ok(result);
    }

    /** 切换写操作确认；写入 Nacos 设置文件，运行期按 TTL 生效，无需重启。 */
    @PutMapping("/action-confirm")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse setActionConfirm(
            @RequestBody ActionConfirmRequest request,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        registry.setActionConfirm(enabled, revision);
        auditService.record(
                "agent.action-confirm",
                "agent_settings",
                "actionConfirm",
                null,
                Map.of("enabled", enabled));
        return ApiResponse.ok(Map.of("enabled", enabled, "source", "NACOS"));
    }

    public record ActionConfirmRequest(Boolean enabled) {}

    /** 可绑定技能清单，供编辑表单的技能多选；目录仓库只读 manifest，不拉取正文。 */
    @GetMapping("/skills")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse skills() {
        List<Map<String, String>> skills =
                skillCatalog() != null
                        ? skillCatalog().listManifests().stream()
                                .map(this::skillOption)
                                .toList()
                        : skillRepository.getAllSkills().stream().map(this::skillOption).toList();
        return ApiResponse.ok(skills);
    }

    @PostMapping
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse create(
            @RequestBody AgentDefinition input,
            @RequestParam(name = "revision", required = false) String revision,
            HttpServletRequest request) {
        AuditContext.markRevision(revision);
        AgentDefinition validated = validate(input, null);
        AgentDefinition saved = registry.save(validated, operator(request), revision, true);
        auditService.record("agent.create", "agent_definition", saved.id(), null, saved);
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse update(
            @PathVariable String id,
            @RequestBody AgentDefinition input,
            @RequestParam(name = "clearModel", defaultValue = "false") boolean clearModel,
            @RequestParam(name = "clearTools", defaultValue = "false") boolean clearTools,
            @RequestParam(name = "revision", required = false) String revision,
            HttpServletRequest request) {
        AuditContext.markRevision(revision);
        AgentDefinition before = find(id);
        AgentDefinition validated = validate(input, id);
        AgentDefinition saved =
                registry.save(
                        validated, operator(request), revision, !clearModel, !clearTools);
        auditService.record("agent.update", "agent_definition", saved.id(), before, saved);
        if (!before.model().equals(saved.model())) {
            auditService.record(
                    "agent.model.change",
                    "agent_definition",
                    saved.id(),
                    before.model(),
                    saved.model());
        }
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse delete(
            @PathVariable String id,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        AgentDefinition before = find(id);
        if (!registry.delete(id, revision)) {
            throw new PayrollException("智能体不存在：" + id);
        }
        auditService.record(
                "agent.delete", "agent_definition", id, before, Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    /** 版本时间线（S2）：current 标记当前生效版本，回滚入口按它置灰。 */
    @GetMapping("/{id}/versions")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse versions(@PathVariable String id) {
        find(id);
        return ApiResponse.ok(registry.versions(id));
    }

    /** 回滚到指定版本：追加新版本行、历史不改，写后运行期缓存失效。 */
    @PostMapping("/{id}/rollback")
    @RequirePermission(Permissions.AGENT_MANAGE)
    public ApiResponse rollback(
            @PathVariable String id,
            @RequestParam("versionNo") int versionNo,
            @RequestParam(name = "revision", required = false) String revision,
            HttpServletRequest request) {
        AuditContext.markRevision(revision);
        AgentDefinition before = find(id);
        AgentDefinition restored = registry.rollback(id, versionNo, revision, operator(request));
        auditService.record("agent.rollback", "agent_definition", id, before, restored);
        return ApiResponse.ok(restored);
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
        Set<String> knownSkills;
        if (skillCatalog() != null) {
            knownSkills = skillCatalog().listManifests().stream()
                    .map(SkillManifest::id)
                    .collect(Collectors.toUnmodifiableSet());
        } else {
            knownSkills = Set.copyOf(skillRepository.getAllSkillNames());
        }
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
        List<String> capabilities = normalizeCapabilities(input.capabilities());
        List<String> tools = BuiltinToolCatalog.normalize(input.tools());
        AgentModelSettings model = validateModel(input.model());
        AgentDefinition definition =
                new AgentDefinition(
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
                        input.updatedAt(),
                        capabilities,
                        tools,
                        model);
        AgentPublishGate.check(definition);
        return definition;
    }

    /** 智能体级模型只覆盖控制面；连接必须存在且启用，数值范围与连接保存保持一致。 */
    private AgentModelSettings validateModel(AgentModelSettings input) {
        if (input == null || input.isEmpty()) {
            return AgentModelSettings.none();
        }
        if (input.numCtx() != null && input.numCtx() < 512) {
            throw new PayrollException("模型上下文不能小于 512");
        }
        if (input.numPredict() != null && input.numPredict() <= 0) {
            throw new PayrollException("最大输出 token 必须大于 0");
        }
        if (input.temperature() != null
                && (input.temperature() < 0D || input.temperature() > 2D)) {
            throw new PayrollException("温度必须在 0 到 2 之间");
        }
        if (input.timeoutSeconds() != null && input.timeoutSeconds() <= 0) {
            throw new PayrollException("超时时间必须大于 0 秒");
        }
        if (!input.connectionId().isBlank()) {
            ModelConnection connection =
                    modelRegistry
                            .find(input.connectionId())
                            .orElseThrow(
                                    () ->
                                            new ModelConfigException(
                                                    ModelConfigException.CONNECTION_UNAVAILABLE,
                                                    "未知模型连接：" + input.connectionId()));
            if (!connection.isEnabled()) {
                throw new ModelConfigException(
                        ModelConfigException.CONNECTION_UNAVAILABLE,
                        "模型连接已停用：" + input.connectionId());
            }
        }
        return input;
    }

    /** 能力声明统一小写去重，避免同一能力因大小写不同被判定为未声明。 */
    private List<String> normalizeCapabilities(List<String> declared) {
        List<String> capabilities = declared.stream()
                .map(capability -> capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT))
                .filter(capability -> !capability.isBlank())
                .distinct()
                .toList();
        if (capabilities.size() > MAX_CAPABILITIES) {
            throw new PayrollException("能力声明最多 " + MAX_CAPABILITIES + " 项");
        }
        for (String capability : capabilities) {
            if (capability.length() > MAX_CAPABILITY_LENGTH) {
                throw new PayrollException("单项能力不能超过 " + MAX_CAPABILITY_LENGTH + " 个字符");
            }
            if (!CAPABILITY_PATTERN.matcher(capability).matches()) {
                throw new PayrollException("能力声明只能包含小写字母、数字、冒号、下划线和连字符：" + capability);
            }
        }
        return capabilities;
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

    private Map<String, String> skillOption(SkillManifest manifest) {
        Map<String, String> option = new LinkedHashMap<>();
        option.put("id", manifest.id());
        option.put("description", manifest.description());
        option.put("source", manifest.source());
        return option;
    }

    private PayrollSkillCatalog skillCatalog() {
        return skillRepository instanceof PayrollSkillCatalog catalog ? catalog : null;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
