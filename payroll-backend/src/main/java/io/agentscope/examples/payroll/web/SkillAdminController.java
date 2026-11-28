package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollTooLargeException;
import io.agentscope.examples.payroll.domain.SandboxUnavailableException;
import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.SandboxClient;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.SkillCreationOriginStore;
import io.agentscope.examples.payroll.skill.SkillDataState;
import io.agentscope.examples.payroll.skill.SkillDataStateStore;
import io.agentscope.examples.payroll.skill.SkillDetail;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillIds;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillInitRequirements;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillMetrics;
import io.agentscope.examples.payroll.skill.SkillProjectParser;
import io.agentscope.examples.payroll.skill.SkillQuality;
import io.agentscope.examples.payroll.skill.SkillStore;
import io.agentscope.examples.payroll.skill.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillTemplates;
import io.agentscope.examples.payroll.skill.SkillVersion;
import io.agentscope.examples.payroll.skill.SkillVersionContent;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.Permissions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 技能管理接口：读写只依赖 {@link SkillStore} 端口，写操作需要 skill:manage 权限。 */
@RestController
@RequestMapping("/api/admin/skills")
public class SkillAdminController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    /** 管理台路由已占用的段名，技能再叫这些名字会与管理页冲突。 */
    private static final Set<String> RESERVED_IDS = Set.of("new", "import", "templates", "edit");

    private static final Logger log = LoggerFactory.getLogger(SkillAdminController.class);

    private final SkillStore store;
    private final NacosAgentRegistry agentRegistry;
    private final AuditService auditService;
    private final SkillImportService importService;
    private final SkillDataStateStore dataStateStore;
    private final SkillCreationOriginStore originStore;
    private final SkillTemplates templates;
    private final H2AdminRepository adminRepository;
    private final SandboxGateway gateway;
    /** 已提交但尚未观测到终态的初始化，用于异步补记 skill.init.finish。 */
    private final Map<String, Long> pendingInit = new ConcurrentHashMap<>();

    @Autowired
    public SkillAdminController(
            SkillStore store,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService,
            SkillDataStateStore dataStateStore,
            SkillCreationOriginStore originStore,
            SkillTemplates templates,
            H2AdminRepository adminRepository,
            SandboxGateway gateway) {
        this.store = store;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.importService = importService;
        this.dataStateStore = dataStateStore == null ? SkillDataStateStore.none() : dataStateStore;
        this.originStore = originStore == null ? SkillCreationOriginStore.none() : originStore;
        this.templates = templates == null ? new SkillTemplates() : templates;
        this.adminRepository = adminRepository;
        this.gateway =
                gateway == null
                        ? new SandboxClient(SkillExecutionSettings.fromEnvironment())
                        : gateway;
    }

    /** 单元测试与旧调用点：不接数据状态/模板/用户展示名，行为退化为「未知」。 */
    SkillAdminController(
            SkillStore store,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService,
            SandboxGateway gateway) {
        this(
                store,
                agentRegistry,
                auditService,
                importService,
                SkillDataStateStore.none(),
                SkillCreationOriginStore.none(),
                new SkillTemplates(),
                null,
                gateway);
    }

    /**
     * 列表同时返回存储状态、规模指标、绑定与数据状态缓存；支持关键字/来源/视图/数据状态过滤、排序与分页。
     * 默认每页 50 条，最多 200 条，避免技能规模化后一次渲染全部条目。
     *
     * <p>请求绝不调用沙箱：数据状态只读 {@code skill_data_state}，由数据页与后台刷新器回写（规格 D8）。
     */
    @GetMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String dataStatus,
            @RequestParam(required = false) String sort) {
        List<SkillSummary> all = store.summaries();
        Set<String> boundSkillIds = boundSkillIds(all);
        Map<String, SkillDataState> states = loadDataStates();
        boolean sandboxEnabled = gateway.enabled();
        List<SkillSummary> matched =
                sortSummaries(
                        filterSummaries(
                                all,
                                keyword,
                                source,
                                view,
                                dataStatus,
                                boundSkillIds,
                                states,
                                sandboxEnabled),
                        sort);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long fromLong = (long) (pageNo - 1) * pageSize;
        int from = (int) Math.min(fromLong, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        List<SkillSummary> pageSkills = matched.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writable", store.writable());
        result.put("location", store.location());
        result.put("sandboxEnabled", sandboxEnabled);
        result.put("total", matched.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("stats", scaleStats(all, boundSkillIds, states, sandboxEnabled));
        result.put("skills", pageSkills);
        result.put(
                "bindings",
                bindingsFor(pageSkills.stream().map(SkillSummary::id).toList()));
        result.put("dataStates", dataStatesOf(pageSkills, states));
        result.put("users", userNames(updatedByOf(pageSkills)));
        return ApiResponse.ok(result);
    }

    /** 兼容旧调用（没有 {@code dataStatus} 过滤）。 */
    ApiResponse list(
            String keyword,
            Integer page,
            Integer size,
            String source,
            String view,
            String sort) {
        return list(keyword, page, size, source, view, null, sort);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse detail(@PathVariable String id) {
        return ApiResponse.ok(store.detail(id));
    }

    /**
     * 工作台概览（规格 §6.3.2）：身份、健康、下一步、最近变更一次取齐。
     *
     * <p>这里<b>不</b>实时调用沙箱——健康里的数据状态读的是 {@code skill_data_state}，
     * 数据页签自己实时查询并回写；否则每开一次概览都要等一次沙箱往返。
     * 版本时间线失败只补 {@code warnings}，不让整个概览 5xx。
     */
    @GetMapping("/{id}/overview")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse overview(@PathVariable String id) {
        SkillDetail detail = store.detail(id);
        SkillDataState state = findDataState(id);
        List<AgentDefinition> agents = boundAgents(id);
        String setupPath = setupPathOf(detail);
        Boolean needsData = effectiveNeedsData(detail, setupPath);
        long declaredMax = SkillProjectParser.dataMaxBytes(detail.metadata());
        List<String> warnings = new ArrayList<>();
        List<SkillVersion> versions;
        try {
            versions = store.versions(id);
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取版本时间线失败 skill={}：{}", id, e.getMessage());
            versions = List.of();
            warnings.add("版本时间线读取失败");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", detail.id());
        result.put("sandboxEnabled", gateway.enabled());
        result.put("identity", identityMap(detail));
        result.put("health", healthMap(detail, state, agents, setupPath, needsData, declaredMax));
        result.put("checklist", checklist(detail, state, agents, needsData, declaredMax));
        result.put("timeline", timeline(versions, detail.versionNo()));
        result.put("users", userNames(provenanceOf(detail, versions)));
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }
        return ApiResponse.ok(result);
    }

    /** 绑定页签（规格 §6.3.3）：只回答「哪些智能体引用了它」。 */
    @GetMapping("/{id}/bindings")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse bindings(@PathVariable String id) {
        SkillDetail detail = store.detail(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", detail.id());
        result.put("bindings", bindingMaps(boundAgents(id)));
        return ApiResponse.ok(result);
    }

    /**
     * 新建页的模板卡（规格 §6.3.5）。模板是代码资源，不写库；{@code usedCount/lastUsedAt}
     * 来自 {@code skill_creation_origin}，{@code recent} 只取当前操作者的最近三条。
     */
    @GetMapping("/templates")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse templates() {
        Map<String, SkillCreationOriginStore.Origin> origins = safeOrigins();
        Map<String, Integer> usedCounts = new LinkedHashMap<>();
        Map<String, Long> lastUsed = new LinkedHashMap<>();
        origins.forEach(
                (skillId, origin) -> {
                    String templateId = origin.templateId();
                    if (templateId == null || templateId.isBlank()) {
                        return;
                    }
                    usedCounts.merge(templateId, 1, Integer::sum);
                    lastUsed.merge(templateId, origin.createdAt(), Math::max);
                });
        List<Map<String, Object>> items =
                templates.list().stream()
                        .map(template -> templateMap(template, usedCounts, lastUsed))
                        .toList();
        String actor = operator();
        List<Map<String, Object>> recent = new ArrayList<>();
        if (!actor.isBlank()) {
            Set<String> seen = new HashSet<>();
            origins.values().stream()
                    .filter(origin -> actor.equals(origin.operator()))
                    .sorted(
                            Comparator.comparingLong(SkillCreationOriginStore.Origin::createdAt)
                                    .reversed())
                    .forEach(
                            origin -> {
                                SkillTemplates.Template template =
                                        templates.find(origin.templateId());
                                if (template == null || !seen.add(template.id())) {
                                    return;
                                }
                                if (recent.size() < 3) {
                                    recent.add(templateMap(template, usedCounts, lastUsed));
                                }
                            });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", items);
        result.put("recent", recent);
        return ApiResponse.ok(result);
    }

    /**
     * 从模板新建技能（规格 §6.3.5）：模板正文重新过一遍保存门禁，重复 ID 走统一 409。
     * 来源记录失败只丢「最近使用」，不能把已经落库的技能改成 5xx。
     */
    @PostMapping("/from-template")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse createFromTemplate(@RequestBody FromTemplateInput input) {
        if (input == null) {
            throw new PayrollException("请求体不能为空");
        }
        SkillTemplates.Template template = templates.require(input.templateId());
        String id = requireSkillId(input.id());
        String description =
                input.description() == null || input.description().isBlank()
                        ? template.description()
                        : input.description().trim();
        SkillSummary saved =
                store.create(
                        id,
                        description,
                        template.body(),
                        Boolean.TRUE.equals(input.executionEnabled()),
                        operator());
        try {
            originStore.record(saved.id(), template.id(), operator());
        } catch (RuntimeException e) {
            log.warn("[Skill] 记录模板来源失败 skill={}：{}", saved.id(), e.getMessage());
        }
        Map<String, Object> after = meta(saved);
        after.put("templateId", template.id());
        putVersion(after, persisted(saved.id()));
        auditService.record("skill.create", "skill", saved.id(), null, after);
        return ApiResponse.ok(saved);
    }

    /**
     * 读取单个参考文件正文，仅供管理台编辑；路径越界由注册表拒绝。
     *
     * <p>{@code ETag} 取内容的 sha256（§13）：与 {@code skill_resource.sha256} 同算法同口径，文件一换
     * 指纹必换，管理台重开同一个文件可以按 {@code If-None-Match} 拿 304 省掉一次正文传输。现算而不是从库里
     * 读列，是因为 Nacos 源没有清单——两条读路径要给出同一个指纹。
     */
    @GetMapping("/{id}/files/{*path}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ResponseEntity<ApiResponse> file(
            @PathVariable String id,
            @PathVariable String path,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        String relative = relativePath(path);
        String content = store.fileContent(id, relative);
        String etag = '"' + SkillManifests.contentHash(content) + '"';
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("path", relative);
        result.put("content", content);
        return ResponseEntity.ok().eTag(etag).body(ApiResponse.ok(result));
    }

    /**
     * 版本时间线（§13）。只读，因此不写审计——本控制器的读接口一律不留痕，与 {@code detail} 一致。
     * Nacos 源没有版本行，返回空列表而不是报错，前端据此隐藏「回滚」。
     */
    @GetMapping("/{id}/versions")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse versions(@PathVariable String id) {
        List<SkillVersion> versions = store.versions(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", SkillIds.normalize(id));
        result.put("total", versions.size());
        result.put("versions", versions);
        return ApiResponse.ok(result);
    }

    /**
     * 单个历史版本（规格 §6.3.4）：编辑器「对比 vN」的基线、版本页签的只读预览都走这里。
     * 只读不写审计；Nacos/内置没有版本行时由存储抛 404，前端据此隐藏「对比」。
     */
    @GetMapping("/{id}/versions/{versionNo}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse versionContent(
            @PathVariable String id, @PathVariable int versionNo) {
        SkillVersionContent version = store.versionContent(id, versionNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", SkillIds.normalize(id));
        result.put("versionNo", version.versionNo());
        result.put("content", version.content());
        result.put("changeNote", version.changeNote());
        result.put("createdBy", version.createdBy());
        result.put("createdAt", version.createdAt());
        result.put("contentLength", version.contentLength());
        result.put("current", version.current());
        result.put("files", version.files());
        return ApiResponse.ok(result);
    }

    /**
     * 回滚到指定版本（§13）：与保存同样带 {@code revision} 令牌，409 走统一契约。
     * 审计的 {@code fromVersionNo} 取自写前快照、{@code toVersionNo} 取自写后快照，
     * 后者读不到时省略——回滚已经提交，不能因为补一个审计字段把成功改成 5xx。
     */
    @PostMapping("/{id}/versions/{versionNo}/rollback")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse rollback(
            @PathVariable String id,
            @PathVariable int versionNo,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        SkillDetail before = store.detail(id);
        SkillSummary saved = store.rollback(id, versionNo, revision, operator());
        SkillDetail after = persisted(saved.id());
        Map<String, Object> beforeMeta = meta(before);
        beforeMeta.put("fromVersionNo", before.versionNo());
        Map<String, Object> afterMeta = meta(saved);
        putVersion(afterMeta, after);
        if (after != null) {
            afterMeta.put("toVersionNo", after.versionNo());
        }
        auditService.record("skill.version.rollback", "skill", saved.id(), beforeMeta, afterMeta);
        return ApiResponse.ok(saved);
    }

    /** 全量替换文件包；空列表等于删除文件包。 */
    @PutMapping("/{id}/files")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse replaceFiles(
            @PathVariable String id,
            @RequestBody FilesInput input,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        List<SkillFilePackage.SkillFile> files = input == null || input.files() == null
                ? List.of()
                : input.files().stream()
                        .map(file -> new SkillFilePackage.SkillFile(file.path(), file.content()))
                        .toList();
        // 文件包只是详情的一部分，走 detail 一次取回旧快照与 version_no，省掉第二次查询。
        SkillDetail before = store.detail(id);
        List<SkillFilePackage.Summary> after =
                store.replaceFiles(id, files, revision, operator());
        Map<String, Object> beforeMeta = filesMeta(before.files());
        putVersion(beforeMeta, before);
        Map<String, Object> afterMeta = filesMeta(after);
        putVersion(afterMeta, persisted(id));
        auditService.record("skill.files.update", "skill", id, beforeMeta, afterMeta);
        return ApiResponse.ok(afterMeta);
    }

    @DeleteMapping("/{id}/files/{*path}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse deleteFile(
            @PathVariable String id,
            @PathVariable String path,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        String relative = relativePath(path);
        SkillDetail before = store.detail(id);
        store.deleteFile(id, relative, revision, operator());
        Map<String, Object> beforeMeta = new LinkedHashMap<>();
        beforeMeta.put("path", relative);
        putVersion(beforeMeta, before);
        Map<String, Object> afterMeta = new LinkedHashMap<>();
        afterMeta.put("deleted", relative);
        putVersion(afterMeta, persisted(id));
        auditService.record("skill.files.delete", "skill", id, beforeMeta, afterMeta);
        return ApiResponse.ok(afterMeta);
    }

    /**
     * 单文件写（UX 规格 A2）：编辑器保存一个文件只发一次请求，不再让前端先读回整包再整包覆盖。
     *
     * <p>返回体带刷新后的 {@code revision}，连续保存第二个文件不用回详情页取令牌；令牌不一致仍走
     * {@link PayrollConflictException} 的 409 契约，{@code currentRevision} 由统一处理器补上。
     */
    @PutMapping("/{id}/files/{*path}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse writeFile(
            @PathVariable String id,
            @PathVariable String path,
            @RequestBody FileInput input,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        String relative = relativePath(path);
        SkillDetail before = store.detail(id);
        List<SkillFilePackage.Summary> files =
                store.writeFile(
                        id,
                        relative,
                        input == null || input.content() == null ? "" : input.content(),
                        revision,
                        operator());
        Map<String, Object> after = filesMeta(files);
        SkillDetail refreshed = persisted(id);
        putVersion(after, refreshed);
        auditService.record("skill.files.update", "skill", id, filesMeta(before.files()), after);
        Map<String, Object> result = new LinkedHashMap<>(after);
        result.put("id", id);
        result.put("path", relative);
        result.put("revision", refreshed == null ? "" : refreshed.revision());
        return ApiResponse.ok(result);
    }

    @PostMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse create(@RequestBody SkillInput input) {
        if (input == null) {
            throw new PayrollException("请求体不能为空");
        }
        String id = requireSkillId(input.id());
        String templateId = input.templateId() == null ? null : input.templateId().trim();
        if (templateId != null && !templateId.isEmpty() && templates.find(templateId) == null) {
            throw new PayrollException("未知模板：" + templateId);
        }
        SkillSummary saved =
                store.create(
                        id,
                        input.description(),
                        input.content(),
                        Boolean.TRUE.equals(input.executionEnabled()),
                        operator());
        if (templateId != null && !templateId.isEmpty()) {
            try {
                originStore.record(saved.id(), templateId, operator());
            } catch (RuntimeException e) {
                log.warn("[Skill] 记录模板来源失败 skill={}：{}", saved.id(), e.getMessage());
            }
        }
        Map<String, Object> after = meta(saved);
        if (templateId != null && !templateId.isEmpty()) {
            after.put("templateId", templateId);
        }
        putVersion(after, persisted(saved.id()));
        auditService.record("skill.create", "skill", saved.id(), null, after);
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse update(
            @PathVariable String id,
            @RequestBody SkillInput input,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        SkillDetail before = store.detail(id);
        SkillSummary saved =
                store.update(
                        id,
                        input.description(),
                        input.content(),
                        Boolean.TRUE.equals(input.executionEnabled()),
                        revision,
                        input.changeNote(),
                        operator());
        Map<String, Object> after = meta(saved);
        putVersion(after, persisted(saved.id()));
        auditService.record("skill.update", "skill", saved.id(), meta(before), after);
        return ApiResponse.ok(saved);
    }

    /**
     * 从 Git 仓库导入目录式技能项目；{@code dryRun} 只解析并返回预览，不落库。
     *
     * <p>{@code revision} 与保存走同一个查询参数契约：整包覆盖最容易盖掉别人刚改的一版。
     * 注意这里可能返回两类 409——「技能已存在，覆盖需要显式确认」不带 {@code currentRevision}，
     * 令牌不一致才带，前端必须分开处理（§13-6）。
     */
    @PostMapping("/import/git")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse importGit(
            @RequestBody GitImportInput input,
            @RequestParam(name = "revision", required = false) String revision) {
        if (input == null) {
            throw new PayrollException("请求体不能为空");
        }
        boolean dryRun = Boolean.TRUE.equals(input.dryRun());
        // dryRun 一个字节都不写，标了 revision 只会让审计把「预览」记成一次成功的条件写入。
        if (!dryRun) {
            AuditContext.markRevision(revision);
        }
        long started = System.nanoTime();
        SkillImportService.ImportResult result =
                importService.importGit(
                        input.url(),
                        input.ref(),
                        input.id(),
                        Boolean.TRUE.equals(input.overwrite()),
                        dryRun,
                        revision,
                        operator());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        if (!result.dryRun()) {
            Map<String, Object> after = importMeta(result);
            after.put("source", "git");
            after.put("url", input.url());
            after.put("ref", input.ref());
            putVersion(after, persisted(result.id()));
            auditService.record("skill.import", "skill", result.id(), null, after);
        }
        return ApiResponse.ok(
                importResponse(
                        result, "git", input.ref(), result.commitSha(), elapsedMillis));
    }

    /** 从 ZIP 包导入目录式技能项目；{@code dryRun} 只解析并返回预览，不落库。409 分两类，见 {@link #importGit}。 */
    @PostMapping("/import/zip")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse importZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) Boolean overwrite,
            @RequestParam(required = false) Boolean dryRun,
            @RequestParam(name = "revision", required = false) String revision) {
        if (file == null || file.isEmpty()) {
            throw new PayrollException("请选择要导入的 ZIP 文件");
        }
        if (!Boolean.TRUE.equals(dryRun)) {
            AuditContext.markRevision(revision);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("payroll-skill-upload-", ".zip");
            file.transferTo(temp.toFile());
            long started = System.nanoTime();
            SkillImportService.ImportResult result =
                    importService.importZip(
                            temp,
                            id,
                            Boolean.TRUE.equals(overwrite),
                            Boolean.TRUE.equals(dryRun),
                            revision,
                            operator());
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            if (!result.dryRun()) {
                Map<String, Object> after = importMeta(result);
                after.put("source", "zip");
                after.put("fileName", file.getOriginalFilename());
                putVersion(after, persisted(result.id()));
                auditService.record("skill.import", "skill", result.id(), null, after);
            }
            return ApiResponse.ok(
                    importResponse(result, "zip", null, null, elapsedMillis));
        } catch (IOException e) {
            throw new PayrollException("保存上传文件失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // 临时文件清理失败不影响导入结果。
                }
            }
        }
    }

    /** 技能数据工作区状态；沙箱未启用时返回 UNKNOWN 与可操作提示。 */
    @GetMapping("/{id}/data")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse dataStatus(@PathVariable String id) {
        SkillDetail detail = store.detail(id);
        String setupPath = setupPathOf(detail);
        SandboxGateway.SkillData state = queryData(id);
        recordFinish(id, detail, state);
        rememberDataState(id, state);
        Map<String, Object> result = dataMap(id, setupPath, state);
        result.put("declaredMaxBytes", SkillProjectParser.dataMaxBytes(detail.metadata()));
        SkillDataState stored = findDataState(id);
        result.put("observedAt", stored == null ? 0L : stored.observedAt());
        return ApiResponse.ok(result);
    }

    /** 提交初始化；数据已就绪时需要 overwrite=true 才会清空重建。 */
    @PostMapping("/{id}/data/init")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse initData(
            @PathVariable String id, @RequestBody(required = false) DataInitInput input) {
        SkillDetail detail = store.detail(id);
        Map<String, String> contents = packageContents(detail);
        String setupPath =
                SkillInitRequirements.setupPath(setupCommand(detail.metadata()), contents.keySet());
        if (setupPath == null) {
            throw new PayrollException("该技能未声明初始化脚本（frontmatter setup 或 setup.sh）");
        }
        if (!gateway.enabled()) {
            throw new PayrollException(
                    "沙箱服务未启用，无法初始化技能数据（检查 PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN）");
        }
        boolean overwrite = input != null && Boolean.TRUE.equals(input.overwrite());
        SandboxGateway.SkillData current = currentData(id);
        if (current != null && SandboxGateway.SkillData.INITIALIZING.equals(current.status())) {
            throw new PayrollConflictException("技能数据正在初始化，请等待完成");
        }
        if (current != null
                && SandboxGateway.SkillData.READY.equals(current.status())
                && !overwrite) {
            throw new PayrollConflictException("技能数据已初始化；重建会清空现有数据，请确认后重试");
        }
        clearData(id);
        List<SandboxGateway.Script> files =
                contents.entrySet().stream()
                        .map(entry -> new SandboxGateway.Script(entry.getKey(), entry.getValue()))
                        .toList();
        long totalBytes =
                contents.values().stream()
                        .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
                        .sum();
        SandboxGateway.SkillData submitted =
                gateway.initSkill(
                        new SandboxGateway.InitRequest(
                                id,
                                setupPath,
                                contents.get(setupPath),
                                files,
                                null,
                                null));
        if (!submitted.initialized()) {
            throw new PayrollException(
                    "初始化提交失败："
                            + (submitted.message() == null || submitted.message().isBlank()
                                    ? submitted.status()
                                    : submitted.message()));
        }
        pendingInit.put(id, System.currentTimeMillis());
        rememberDataState(id, submitted);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("setupPath", setupPath);
        after.put("fileCount", files.size());
        after.put("totalBytes", totalBytes);
        after.put("overwrite", overwrite);
        after.put("status", submitted.status());
        putVersion(after, detail);
        auditService.record(
                "skill.init.start", "skill", id, stateMeta(current), after);
        return ApiResponse.ok(dataMap(id, setupPath, submitted));
    }

    /** 删除技能数据工作区；初始化进行中会被沙箱拒绝。 */
    @DeleteMapping("/{id}/data")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse deleteData(@PathVariable String id) {
        SkillDetail detail = store.detail(id);
        if (!gateway.enabled()) {
            throw new PayrollException("沙箱服务未启用，无法删除技能数据");
        }
        SandboxGateway.SkillData before = currentData(id);
        SandboxGateway.SkillData deleted;
        try {
            deleted = gateway.deleteSkillData(id);
        } catch (IllegalStateException e) {
            throw new PayrollException("删除技能数据失败：" + e.getMessage());
        }
        if (deleted != null
                && (SandboxGateway.SkillData.BUSY.equals(deleted.status())
                        || SandboxGateway.SkillData.UNAVAILABLE.equals(deleted.status()))) {
            throw new PayrollConflictException(
                    deleted.message() == null || deleted.message().isBlank()
                            ? "无法删除技能数据：" + deleted.status()
                            : deleted.message());
        }
        pendingInit.remove(id);
        rememberDataState(id, deleted);
        Map<String, Object> beforeMeta = stateMeta(before);
        Map<String, Object> afterMeta = stateMeta(deleted);
        putVersion(beforeMeta, detail);
        putVersion(afterMeta, detail);
        auditService.record("skill.data.delete", "skill", id, beforeMeta, afterMeta);
        return ApiResponse.ok(dataMap(id, setupPathOf(detail), deleted));
    }

    /** 浏览 data/ 目录树；初始化中由沙箱返回 409，未初始化返回 404。 */
    @GetMapping("/{id}/data/tree")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse dataTree(
            @PathVariable String id,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "depth", required = false) Integer depth) {
        SkillDetail detail = store.detail(id);
        requireDataBrowsable(detail);
        SandboxGateway.DataTree tree;
        try {
            tree =
                    gateway.dataTree(
                            detail.id(),
                            path,
                            depth == null ? SkillDataProtocol.DEFAULT_DEPTH : depth);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            throw new SandboxUnavailableException(e.getMessage(), e);
        }
        return ApiResponse.ok(treeMap(detail.id(), tree));
    }

    /** 预览 data/ 中的单个文本文件；二进制只回元数据，读取行为记入审计（不含正文）。 */
    @GetMapping("/{id}/data/file")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse dataFile(
            @PathVariable String id,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "maxBytes", required = false) Integer maxBytes) {
        SkillDetail detail = store.detail(id);
        requireDataBrowsable(detail);
        SandboxGateway.DataFile file;
        try {
            file =
                    gateway.dataFile(
                            detail.id(),
                            path,
                            maxBytes == null
                                    ? SkillDataProtocol.DEFAULT_PREVIEW_BYTES
                                    : maxBytes);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            throw new SandboxUnavailableException(e.getMessage(), e);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("skillId", detail.id());
        after.put("path", file.path());
        after.put("sizeBytes", file.size());
        after.put("truncated", file.truncated());
        after.put("isText", file.isText());
        auditService.record("skill.data.read", "skill", detail.id(), null, after);
        return ApiResponse.ok(fileMap(file));
    }

    /**
     * 上传文件到 data/——与「重建数据」写入的是同一个目录。正文先落临时文件并计算 SHA-256，
     * 再用摘要与长度签名转发给沙箱，避免为计算摘要把大文件整体读进内存。
     * 初始化进行中由沙箱返回 409；重建会清空 data/，包括这里上传的文件。
     */
    @PostMapping("/{id}/data/file")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse uploadDataFile(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "overwrite", required = false) Boolean overwrite) {
        SkillDetail detail = store.detail(id);
        requireDataWritable(detail);
        if (file == null || file.isEmpty()) {
            throw new PayrollException("请选择要上传的文件");
        }
        String relative =
                SkillDataProtocol.requireUploadFile(
                        path == null || path.isBlank() ? file.getOriginalFilename() : path,
                        "数据文件");
        Path temp = null;
        try {
            temp = Files.createTempFile("payroll-data-upload-", ".bin");
            DigestResult staged = stage(file, temp);
            SandboxGateway.DataWrite result;
            try (InputStream body = Files.newInputStream(temp)) {
                result =
                        gateway.uploadDataFile(
                                detail.id(),
                                relative,
                                Boolean.TRUE.equals(overwrite),
                                staged.size(),
                                staged.sha256(),
                                body);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("skillId", detail.id());
            after.put("path", result.path());
            after.put("sizeBytes", result.size());
            after.put("sha256", result.sha256());
            after.put("overwritten", result.overwritten());
            putVersion(after, detail);
            auditService.record("skill.data.upload", "skill", detail.id(), null, after);
            return ApiResponse.ok(dataWriteMap(result, file.getOriginalFilename()));
        } catch (IOException e) {
            throw new PayrollException("保存上传文件失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响上传结果。
                }
            }
        }
    }

    /** 删除 data/ 中的单个文件；与上传共用路径，重建同样会清掉这些文件。 */
    @DeleteMapping("/{id}/data/file")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse deleteDataFile(
            @PathVariable String id, @RequestParam(name = "path", required = false) String path) {
        SkillDetail detail = store.detail(id);
        requireDataWritable(detail);
        SandboxGateway.DataWrite deleted = gateway.deleteDataFile(detail.id(), path);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("skillId", detail.id());
        before.put("path", deleted.path());
        before.put("sizeBytes", deleted.size());
        before.put("sha256", deleted.sha256());
        putVersion(before, detail);
        auditService.record("skill.data.upload.delete", "skill", detail.id(), before, null);
        return ApiResponse.ok(dataWriteMap(deleted, null));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse delete(
            @PathVariable String id,
            @RequestParam(name = "revision", required = false) String revision) {
        AuditContext.markRevision(revision);
        SkillDetail before = store.detail(id);
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
        store.delete(id, revision);
        forgetSkillState(id);
        auditService.record("skill.delete", "skill", id, meta(before), Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    /**
     * 视图与数据状态的筛选条件。放在服务端而不是页面里，因为分页在前端做就只能筛当前页——「被引用 7 条」
     * 点进去却只看到 2 条，是比不做更差的谎。{@code bound} 由调用方按同一份目录算好，避免重复读注册表。
     */
    private static List<SkillSummary> filterSummaries(
            List<SkillSummary> skills,
            String keyword,
            String source,
            String view,
            String dataStatus,
            Set<String> boundSkillIds,
            Map<String, SkillDataState> states,
            boolean sandboxEnabled) {
        String needle =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim().toLowerCase(Locale.ROOT);
        String sourceFilter = source == null || source.isBlank() ? null : source.trim();
        String viewFilter = view == null || view.isBlank() ? "all" : view.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "custom", "builtin", "overridden", "bound", "attention", "unbound")
                .contains(viewFilter)) {
            throw new PayrollException(
                    "不支持的视图：" + view
                            + "（可选 all/custom/builtin/overridden/bound/attention/unbound）");
        }
        String dataFilter = dataStatus == null || dataStatus.isBlank()
                ? "all"
                : dataStatus.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "ready", "attention", "none").contains(dataFilter)) {
            throw new PayrollException(
                    "不支持的数据状态：" + dataStatus + "（可选 all/ready/attention/none）");
        }
        return skills.stream()
                .filter(skill -> sourceFilter == null
                        || (skill.source() != null && skill.source().equalsIgnoreCase(sourceFilter)))
                .filter(skill -> switch (viewFilter) {
                    case "custom" -> !skill.builtin();
                    case "builtin" -> skill.builtin();
                    case "overridden" -> skill.overridden();
                    case "bound" -> boundSkillIds.contains(skill.id());
                    case "attention" ->
                            "attention".equals(
                                    dataBucket(skill, states.get(skill.id()), sandboxEnabled));
                    case "unbound" -> !boundSkillIds.contains(skill.id());
                    default -> true;
                })
                .filter(skill -> dataMatches(
                        dataFilter, dataBucket(skill, states.get(skill.id()), sandboxEnabled)))
                .filter(skill -> needle == null
                        || contains(skill.id(), needle)
                        || contains(skill.description(), needle))
                .toList();
    }

    /** {@code ready} 按规格等于「可用」：数据就绪与无需数据都算。 */
    private static boolean dataMatches(String filter, String bucket) {
        return switch (filter) {
            case "ready" -> "ready".equals(bucket) || "none".equals(bucket);
            case "attention" -> "attention".equals(bucket);
            case "none" -> "none".equals(bucket);
            default -> true;
        };
    }

    /**
     * 单条技能的数据状态归类。{@code needsData=false} 恒为「无需数据」；沙箱关闭或来源无法判定时为
     * 「未知」——不能把它算进待处理，那会把「不知道」渲染成「坏了」。
     */
    private static String dataBucket(
            SkillSummary skill, SkillDataState state, boolean sandboxEnabled) {
        if (Boolean.FALSE.equals(skill.needsData())) {
            return "none";
        }
        if (!sandboxEnabled || skill.needsData() == null) {
            return "unknown";
        }
        String status = state == null ? SkillDataState.UNKNOWN : state.status();
        if (SkillDataState.READY.equals(status)) {
            return "ready";
        }
        if (SkillDataState.pending(status)) {
            return "initializing";
        }
        return "attention";
    }

    /** 被至少一个智能体引用的技能 ID；与 {@link #scaleStats} 的「被绑定技能」同一口径。 */
    private Set<String> boundSkillIds(List<SkillSummary> all) {
        Set<String> known = all.stream().map(SkillSummary::id).collect(Collectors.toSet());
        return agentRegistry.list().stream()
                .filter(agent -> agent.skillIds() != null)
                .flatMap(agent -> agent.skillIds().stream())
                .filter(known::contains)
                .collect(Collectors.toSet());
    }

    private static List<SkillSummary> sortSummaries(List<SkillSummary> skills, String sort) {
        String key = sort == null || sort.isBlank() ? "id" : sort.trim().toLowerCase(Locale.ROOT);
        Comparator<SkillSummary> byId =
                Comparator.comparing(SkillSummary::id, String.CASE_INSENSITIVE_ORDER);
        Comparator<SkillSummary> comparator =
                switch (key) {
                    case "id" -> byId;
                    case "updated" ->
                            Comparator.comparingLong(SkillSummary::updatedAt).reversed().thenComparing(byId);
                    case "size" ->
                            Comparator.comparingInt(SkillSummary::contentLength).reversed().thenComparing(byId);
                    default ->
                            throw new PayrollException(
                                    "不支持的排序字段：" + sort + "（可选 id/updated/size）");
                };
        return skills.stream().sorted(comparator).toList();
    }

    /** 规模指标：按规格 §6.3.1 的口径全部基于全量目录，不是当前页。 */
    private Map<String, Object> scaleStats(
            List<SkillSummary> all,
            Set<String> bound,
            Map<String, SkillDataState> states,
            boolean sandboxEnabled) {
        List<AgentDefinition> agents = agentRegistry.list();
        long boundAgents =
                agents.stream()
                        .filter(agent -> agent.skillIds() != null && !agent.skillIds().isEmpty())
                        .count();
        long usable = 0;
        long needsAttention = 0;
        long initializing = 0;
        long unknownData = 0;
        for (SkillSummary skill : all) {
            switch (dataBucket(skill, states.get(skill.id()), sandboxEnabled)) {
                case "ready", "none" -> usable++;
                case "attention" -> needsAttention++;
                case "initializing" -> initializing++;
                default -> unknownData++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSkills", all.size());
        stats.put("usable", usable);
        stats.put("needsAttention", needsAttention);
        stats.put("initializing", initializing);
        stats.put("unknownData", unknownData);
        stats.put("unbound", all.size() - bound.size());
        stats.put("boundAgents", boundAgents);
        stats.put("boundSkills", bound.size());
        stats.put("contentBytes", all.stream().mapToLong(SkillSummary::contentLength).sum());
        stats.put("catalogBuildMillis", SkillMetrics.lastCatalogBuildMillis());
        stats.put("cacheHitRatio", SkillMetrics.contentCacheHitRatio());
        Map<String, Object> views = new LinkedHashMap<>();
        views.put("all", all.size());
        views.put("custom", all.stream().filter(skill -> !skill.builtin()).count());
        views.put("builtin", all.stream().filter(SkillSummary::builtin).count());
        views.put("overridden", all.stream().filter(SkillSummary::overridden).count());
        views.put("bound", bound.size());
        views.put("attention", needsAttention);
        views.put("unbound", all.size() - bound.size());
        stats.put("views", views);
        return stats;
    }

    /** 单页的绑定聚合：只查一次注册表，避免逐行 N+1。 */
    private Map<String, List<Map<String, Object>>> bindingsFor(Collection<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Set<String> wanted = new HashSet<>(skillIds);
        Map<String, List<Map<String, Object>>> bindings = new LinkedHashMap<>();
        for (AgentDefinition agent : agentRegistry.list()) {
            if (agent.skillIds() == null) {
                continue;
            }
            for (String skillId : agent.skillIds()) {
                if (wanted.contains(skillId)) {
                    bindings.computeIfAbsent(skillId, key -> new ArrayList<>())
                            .add(bindingMap(agent));
                }
            }
        }
        return bindings;
    }

    private List<AgentDefinition> boundAgents(String skillId) {
        return agentRegistry.list().stream()
                .filter(agent -> agent.skillIds() != null && agent.skillIds().contains(skillId))
                .toList();
    }

    private static List<Map<String, Object>> bindingMaps(List<AgentDefinition> agents) {
        return agents.stream().map(SkillAdminController::bindingMap).toList();
    }

    private static Map<String, Object> bindingMap(AgentDefinition agent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("agentId", agent.id());
        item.put("name", agent.name());
        item.put("description", agent.description() == null ? "" : agent.description());
        return item;
    }

    /** 单页数据状态；无需数据的技能不出现。声明上限来自列表摘要，不再逐条读详情。 */
    private static Map<String, Object> dataStatesOf(
            List<SkillSummary> skills, Map<String, SkillDataState> states) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SkillSummary skill : skills) {
            if (Boolean.FALSE.equals(skill.needsData())) {
                continue;
            }
            SkillDataState state = states.get(skill.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillId", skill.id());
            item.put("status", state == null ? SkillDataState.UNKNOWN : state.status());
            item.put("sizeBytes", state == null ? 0L : state.sizeBytes());
            if (skill.dataMaxBytes() != null) {
                item.put("declaredMaxBytes", skill.dataMaxBytes());
            }
            if (state != null) {
                item.put("exitCode", state.exitCode());
                item.put("startedAt", state.startedAt());
                item.put("finishedAt", state.finishedAt());
                item.put("message", state.message());
                item.put("observedAt", state.observedAt());
            }
            result.put(skill.id(), item);
        }
        return result;
    }

    private static Set<String> updatedByOf(List<SkillSummary> skills) {
        Set<String> names = new HashSet<>();
        for (SkillSummary skill : skills) {
            if (skill.updatedBy() != null && !skill.updatedBy().isBlank()) {
                names.add(skill.updatedBy());
            }
        }
        return names;
    }

    private static Set<String> provenanceOf(SkillDetail detail, List<SkillVersion> versions) {
        Set<String> names = new HashSet<>();
        if (detail.createdBy() != null && !detail.createdBy().isBlank()) {
            names.add(detail.createdBy());
        }
        if (detail.updatedBy() != null && !detail.updatedBy().isBlank()) {
            names.add(detail.updatedBy());
        }
        for (SkillVersion version : versions) {
            if (version.createdBy() != null && !version.createdBy().isBlank()) {
                names.add(version.createdBy());
            }
        }
        return names;
    }

    /** 用户名 → 显示名；仓储缺失或查询失败只丢展示名，接口照常返回。 */
    private Map<String, String> userNames(Set<String> usernames) {
        if (usernames == null || usernames.isEmpty() || adminRepository == null) {
            return Map.of();
        }
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (AppUser user : adminRepository.users()) {
                if (!usernames.contains(user.username())) {
                    continue;
                }
                String display = user.displayName();
                names.put(
                        user.username(),
                        display == null || display.isBlank() ? user.username() : display);
            }
            return names;
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取用户显示名失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, SkillDataState> loadDataStates() {
        try {
            return dataStateStore.findAll();
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取数据状态缓存失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private SkillDataState findDataState(String skillId) {
        try {
            return dataStateStore.find(skillId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取数据状态失败 skill={}：{}", skillId, e.getMessage());
            return null;
        }
    }

    private void rememberDataState(String skillId, SandboxGateway.SkillData state) {
        try {
            dataStateStore.upsert(SkillDataState.of(skillId, state, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("[Skill] 回写数据状态失败 skill={}：{}", skillId, e.getMessage());
        }
    }

    private void forgetSkillState(String skillId) {
        try {
            dataStateStore.delete(skillId);
        } catch (RuntimeException e) {
            log.warn("[Skill] 清理数据状态失败 skill={}：{}", skillId, e.getMessage());
        }
        try {
            originStore.delete(skillId);
        } catch (RuntimeException e) {
            log.warn("[Skill] 清理模板来源失败 skill={}：{}", skillId, e.getMessage());
        }
    }

    private Map<String, SkillCreationOriginStore.Origin> safeOrigins() {
        try {
            return originStore.origins();
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取模板来源失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> templateMap(
            SkillTemplates.Template template,
            Map<String, Integer> usedCounts,
            Map<String, Long> lastUsed) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", template.id());
        item.put("title", template.title());
        item.put("description", template.description());
        item.put("category", template.category());
        item.put("bodyLength", template.bodyLength());
        item.put("body", template.body());
        item.put("usedCount", usedCounts.getOrDefault(template.id(), 0));
        Long usedAt = lastUsed.get(template.id());
        if (usedAt != null) {
            item.put("lastUsedAt", usedAt);
        }
        return item;
    }

    private static String requireSkillId(String raw) {
        String id = SkillIds.normalize(raw);
        if (RESERVED_IDS.contains(id)) {
            throw new PayrollException(
                    "技能 ID 不能使用管理台保留字：" + String.join("、", RESERVED_IDS));
        }
        return id;
    }

    private Map<String, Object> identityMap(SkillDetail detail) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("source", detail.source());
        identity.put("writable", store.writable());
        identity.put("versionNo", detail.versionNo());
        identity.put("contentHash", SkillManifests.contentHash(detail.content()));
        identity.put("createdBy", detail.createdBy());
        identity.put("createdAt", detail.createdAt());
        identity.put("updatedBy", detail.updatedBy());
        identity.put("updatedAt", detail.updatedAt());
        return identity;
    }

    private static Map<String, Object> healthMap(
            SkillDetail detail,
            SkillDataState state,
            List<AgentDefinition> agents,
            String setupPath,
            Boolean needsData,
            long declaredMax) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("needsData", needsData);
        data.put("status", state == null ? SkillDataState.UNKNOWN : state.status());
        data.put("sizeBytes", state == null ? 0L : state.sizeBytes());
        data.put("declaredMaxBytes", declaredMax > 0 ? declaredMax : null);
        data.put("startedAt", state == null ? 0L : state.startedAt());
        data.put("finishedAt", state == null ? 0L : state.finishedAt());
        data.put("observedAt", state == null ? 0L : state.observedAt());
        data.put("message", state == null ? "" : state.message());

        List<SkillFilePackage.Summary> files =
                detail.files() == null ? List.of() : detail.files();
        long scriptCount =
                files.stream()
                        .filter(file -> SkillFilePackage.KIND_SCRIPT.equals(file.kind()))
                        .count();
        Map<String, Object> scripts = new LinkedHashMap<>();
        scripts.put("executionEnabled", detail.executionEnabled());
        scripts.put("count", scriptCount);
        scripts.put("setupCommand", setupPath);

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("count", agents.size());
        bindings.put("agents", bindingMaps(agents));

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("data", data);
        health.put("scripts", scripts);
        health.put("bindings", bindings);
        return health;
    }

    private static List<Map<String, Object>> checklist(
            SkillDetail detail,
            SkillDataState state,
            List<AgentDefinition> agents,
            Boolean needsData,
            long declaredMax) {
        List<Map<String, Object>> items = new ArrayList<>();

        List<SkillQuality.Finding> findings =
                SkillQuality.review(detail.description(), detail.content());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "body");
        body.put("label", "正文通过质量检查");
        body.put("ok", findings.isEmpty());
        body.put(
                "detail",
                detail.versionNo() > 0 ? "v" + detail.versionNo() : detail.content().length() + " 字");
        if (!findings.isEmpty()) {
            body.put("issues", findings);
        }
        body.put("action", "body");
        items.add(body);

        int fileCount = detail.files() == null ? 0 : detail.files().size();
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("key", "files");
        files.put("label", "已添加参考文件");
        files.put("ok", fileCount > 0);
        files.put("detail", fileCount + " 个");
        files.put("action", "files");
        items.add(files);

        String status = state == null ? SkillDataState.UNKNOWN : state.status();
        boolean ready = SkillDataState.READY.equals(status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", "data");
        data.put("label", "数据已就绪");
        if (Boolean.FALSE.equals(needsData)) {
            data.put("ok", true);
            data.put("detail", "无需数据");
        } else {
            data.put("ok", ready);
            data.put(
                    "detail",
                    ready
                            ? formatBytes(state.sizeBytes()) + " / " + formatBytes(declaredMax)
                            : status);
        }
        data.put("action", "data");
        items.add(data);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("key", "binding");
        binding.put("label", "已绑定至少 1 个智能体");
        binding.put("ok", !agents.isEmpty());
        binding.put("detail", agents.size() + " 个");
        binding.put("action", "bindings");
        items.add(binding);
        return items;
    }

    private static List<Map<String, Object>> timeline(
            List<SkillVersion> versions, int currentVersionNo) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SkillVersion version : versions) {
            if (items.size() >= 5) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("versionNo", version.versionNo());
            item.put("changeNote", version.changeNote());
            item.put("createdBy", version.createdBy());
            item.put("createdAt", version.createdAt());
            item.put(
                    "current",
                    version.current() || version.versionNo() == currentVersionNo);
            items.add(item);
        }
        return items;
    }

    private static Boolean effectiveNeedsData(SkillDetail detail, String setupPath) {
        if (detail.needsData() != null) {
            return detail.needsData();
        }
        return setupPath != null;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes >= 1024L * 1024 * 1024) {
            return formatSize(bytes / (1024.0 * 1024 * 1024), "GiB");
        }
        if (bytes >= 1024L * 1024) {
            return formatSize(bytes / (1024.0 * 1024), "MiB");
        }
        if (bytes >= 1024L) {
            return formatSize(bytes / 1024.0, "KiB");
        }
        return bytes + " B";
    }

    private static String formatSize(double value, String unit) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text + " " + unit;
    }

    /**
     * 导入预览（规格 §6.3.6）：逐文件与当前版本指纹比对，响应在真实导入时也同构。
     * 新技能全部 {@code ADDED}；已有技能但读不到指纹时记 {@code UNKNOWN}，不能当成新增。
     */
    private Map<String, Object> importResponse(
            SkillImportService.ImportResult result,
            String kind,
            String ref,
            String resolvedSha,
            long elapsedMillis) {
        Map<String, String> current = Map.of();
        Integer currentVersionNo = null;
        if (result.existing()) {
            try {
                current = store.resourceHashes(result.id());
            } catch (RuntimeException e) {
                log.warn("[Skill] 读取导入前文件指纹失败 skill={}：{}", result.id(), e.getMessage());
            }
            SkillDetail detail = persisted(result.id());
            if (detail != null && detail.versionNo() > 0) {
                currentVersionNo = detail.versionNo();
            }
        }
        List<Map<String, Object>> files = new ArrayList<>();
        long bytes = 0;
        int added = 0;
        int replaced = 0;
        int unchanged = 0;
        int unknown = 0;
        for (SkillFilePackage.Summary file : result.files()) {
            String sha = result.hashes().get(file.path());
            String status;
            if (!result.existing()) {
                status = "ADDED";
            } else if (current.isEmpty()) {
                status = "UNKNOWN";
            } else {
                String previous = current.get(file.path());
                if (previous == null) {
                    status = "ADDED";
                } else if (previous.equals(sha)) {
                    status = "UNCHANGED";
                } else {
                    status = "REPLACED";
                }
            }
            switch (status) {
                case "ADDED" -> added++;
                case "REPLACED" -> replaced++;
                case "UNCHANGED" -> unchanged++;
                default -> unknown++;
            }
            bytes += file.size();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("size", file.size());
            item.put("kind", file.kind());
            if (sha != null) {
                item.put("sha256", sha);
            }
            item.put("status", status);
            files.add(item);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("files", result.files().size());
        totals.put("bytes", bytes);
        totals.put("added", added);
        totals.put("replaced", replaced);
        totals.put("unchanged", unchanged);
        if (unknown > 0) {
            totals.put("unknown", unknown);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", kind);
        if (ref != null && !ref.isBlank()) {
            source.put("ref", ref.trim());
        }
        source.put("resolvedSha", resolvedSha);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", result.id());
        response.put("commitSha", result.commitSha());
        response.put("description", result.description());
        response.put("metadata", result.metadata());
        response.put("source", source);
        response.put("elapsedMillis", elapsedMillis);
        response.put("files", files);
        response.put("totals", totals);
        response.put("setupCommand", result.setupCommand());
        response.put("dataMaxBytes", result.dataMaxBytes());
        response.put("warnings", result.warnings());
        response.put("existing", result.existing());
        response.put("currentVersionNo", currentVersionNo);
        response.put("dryRun", result.dryRun());
        return response;
    }

    private SandboxGateway.SkillData queryData(String id) {
        if (!gateway.enabled()) {
            return new SandboxGateway.SkillData(
                    SandboxGateway.SkillData.UNKNOWN,
                    id,
                    null,
                    0,
                    0,
                    0,
                    "",
                    "沙箱服务未启用（PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN）");
        }
        try {
            return gateway.skillData(id);
        } catch (RuntimeException e) {
            return new SandboxGateway.SkillData(
                    SandboxGateway.SkillData.UNAVAILABLE, id, null, 0, 0, 0, "", e.getMessage());
        }
    }

    private SandboxGateway.SkillData currentData(String id) {
        if (!gateway.enabled()) {
            return null;
        }
        try {
            return gateway.skillData(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 初始化前清空旧工作区；忙或不可用时中止，避免在残留数据上继续初始化。 */
    private void clearData(String id) {
        SandboxGateway.SkillData cleared;
        try {
            cleared = gateway.deleteSkillData(id);
        } catch (IllegalStateException e) {
            throw new PayrollException("无法清理旧工作区：" + e.getMessage());
        }
        if (cleared == null) {
            return;
        }
        if (SandboxGateway.SkillData.BUSY.equals(cleared.status())
                || SandboxGateway.SkillData.UNAVAILABLE.equals(cleared.status())
                || SandboxGateway.SkillData.INVALID.equals(cleared.status())) {
            throw new PayrollConflictException(
                    cleared.message() == null || cleared.message().isBlank()
                            ? "无法清理旧工作区：" + cleared.status()
                            : cleared.message());
        }
    }

    private void recordFinish(
            String id, SkillDetail detail, SandboxGateway.SkillData state) {
        if (state == null) {
            return;
        }
        boolean terminal =
                SandboxGateway.SkillData.READY.equals(state.status())
                        || SandboxGateway.SkillData.FAILED.equals(state.status());
        if (!terminal || pendingInit.remove(id) == null) {
            return;
        }
        Map<String, Object> meta = stateMeta(state);
        putVersion(meta, detail);
        auditService.record("skill.init.finish", "skill", id, null, meta);
    }

    private Map<String, String> packageContents(SkillDetail detail) {
        Map<String, String> contents = new LinkedHashMap<>();
        if (detail.files() == null) {
            return contents;
        }
        for (SkillFilePackage.Summary file : detail.files()) {
            if (!SkillInitProtocol.isInitPath(file.path())) {
                continue;
            }
            contents.put(file.path(), store.fileContent(detail.id(), file.path()));
        }
        return contents;
    }

    private static String setupPathOf(SkillDetail detail) {
        List<SkillFilePackage.Summary> files = detail.files();
        Set<String> paths =
                files == null
                        ? Set.of()
                        : files.stream()
                                .map(SkillFilePackage.Summary::path)
                                .collect(Collectors.toSet());
        return SkillInitRequirements.setupPath(setupCommand(detail.metadata()), paths);
    }

    /**
     * 数据浏览前置：只有 db 源、沙箱启用的技能才有 data/ 可看。技能未声明 setup 时也允许浏览——
     * 人工上传的文件就落在同一个 data/ 目录里。
     */
    private void requireDataBrowsable(SkillDetail detail) {
        if (detail.source() != null && !"db".equalsIgnoreCase(detail.source())) {
            throw new PayrollException("当前来源不提供数据状态，无法浏览 data/");
        }
        if (!gateway.enabled()) {
            throw new PayrollException(
                    "沙箱服务未启用，无法浏览技能数据（检查 PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN）");
        }
    }

    private static String setupCommand(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("setup");
        return raw == null ? null : String.valueOf(raw);
    }

    /** 数据写入前置：db 源且沙箱启用；不要求声明 setup，也不要求 data/ 已初始化。 */
    private void requireDataWritable(SkillDetail detail) {
        if (detail.source() != null && !"db".equalsIgnoreCase(detail.source())) {
            throw new PayrollException("当前来源不提供技能数据目录，请先切换到 db 源技能");
        }
        if (!gateway.enabled()) {
            throw new PayrollException(
                    "沙箱服务未启用，无法上传技能数据（检查 PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN）");
        }
    }

    /** 把上传内容写到临时文件并计算长度与 SHA-256；超过单文件上限立即失败。 */
    private static DigestResult stage(MultipartFile file, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行时缺少 SHA-256 实现", e);
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = file.getInputStream();
                OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > SkillDataProtocol.MAX_UPLOAD_FILE_BYTES) {
                    throw new PayrollTooLargeException(
                            "单文件上限 " + SkillDataProtocol.MAX_UPLOAD_FILE_BYTES + " 字节，请压缩后重试");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new PayrollException("上传文件内容为空");
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private record DigestResult(long size, String sha256) {
    }

    private static Map<String, Object> treeMap(String skillId, SandboxGateway.DataTree tree) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (tree.entries() != null) {
            for (SandboxGateway.DataEntry entry : tree.entries()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.name());
                item.put("path", entry.path());
                item.put("type", entry.type());
                item.put("size", entry.size());
                item.put("modifiedAt", entry.modifiedAt());
                if (entry.isText() != null) {
                    item.put("isText", entry.isText());
                }
                entries.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillId", skillId);
        result.put("path", tree.path());
        result.put("entries", entries);
        result.put("truncated", tree.truncated());
        result.put("entryLimit", tree.entryLimit());
        return result;
    }

    private static Map<String, Object> fileMap(SandboxGateway.DataFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.path());
        result.put("size", file.size());
        result.put("modifiedAt", file.modifiedAt());
        result.put("isText", file.isText());
        if (file.encoding() != null) {
            result.put("encoding", file.encoding());
        }
        result.put("truncated", file.truncated());
        if (file.content() != null) {
            result.put("content", file.content());
        }
        result.put("mime", file.mime());
        return result;
    }

    private static Map<String, Object> dataWriteMap(
            SandboxGateway.DataWrite file, String originalName) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (originalName != null) {
            result.put("originalName", originalName);
        }
        result.put("path", file.path());
        result.put("size", file.size());
        result.put("modifiedAt", file.modifiedAt());
        if (file.sha256() != null) {
            result.put("sha256", file.sha256());
        }
        result.put("overwritten", file.overwritten());
        return result;
    }

    private static Map<String, Object> dataMap(
            String id, String setupPath, SandboxGateway.SkillData state) {
        SandboxGateway.SkillData effective =
                state == null
                        ? new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, id, null, 0, 0, 0, "", "")
                        : state;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillId", id);
        result.put("requiresData", setupPath != null);
        result.put("setupPath", setupPath);
        result.put("status", effective.status());
        result.put("exitCode", effective.exitCode());
        result.put("startedAt", effective.startedAt());
        result.put("finishedAt", effective.finishedAt());
        result.put("sizeBytes", effective.sizeBytes());
        result.put("logTail", effective.logTail() == null ? "" : effective.logTail());
        result.put("message", effective.message() == null ? "" : effective.message());
        return result;
    }

    private static Map<String, Object> stateMeta(SandboxGateway.SkillData state) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (state == null) {
            return meta;
        }
        meta.put("status", state.status());
        meta.put("sizeBytes", state.sizeBytes());
        if (state.exitCode() != null) {
            meta.put("exitCode", state.exitCode());
        }
        if (state.message() != null && !state.message().isBlank()) {
            meta.put("message", state.message());
        }
        return meta;
    }

    private static Map<String, Object> importMeta(SkillImportService.ImportResult result) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileCount", result.files().size());
        meta.put(
                "totalBytes",
                result.files().stream().mapToInt(SkillFilePackage.Summary::size).sum());
        meta.put("overwritten", result.existing());
        if (result.commitSha() != null) {
            meta.put("commitSha", result.commitSha());
        }
        if (result.setupCommand() != null) {
            meta.put("setup", result.setupCommand());
        }
        return meta;
    }

    /**
     * §13-6：审计快照带版本定位信息，便于把一条写操作对应到 {@code skill_version} 的具体序号。
     * Nacos 源是整包发布，既没有版本行（versionNo=0）也没有行级令牌（revision 空串），
     * 此时两键都不写——审计里出现恒为 0 的 versionNo 只会被误读成「第一个版本被覆盖」。
     * 只补缺失键：写接口返回的摘要已经带上本次写入后的 revision，重读快照不该把它盖成别人的新值。
     */
    private static void putVersion(Map<String, Object> meta, String revision, int versionNo) {
        if (revision != null && !revision.isBlank()) {
            meta.putIfAbsent("revision", revision);
        }
        if (versionNo > 0) {
            meta.putIfAbsent("versionNo", versionNo);
        }
    }

    private static void putVersion(Map<String, Object> meta, SkillDetail detail) {
        if (detail != null) {
            putVersion(meta, detail.revision(), detail.versionNo());
        }
    }

    /**
     * 本次写入的操作者，取自 {@code AuditFilter} 建立的请求上下文——与 {@code audit_log.actor_name}
     * 同一个来源，不会出现「审计记的是张三、技能行上写的是李四」。上下文缺失（内部调用、无过滤器
     * 的测试）时返回空串，由存储层落 NULL。
     */
    private static String operator() {
        AuditContext.State context = AuditContext.current();
        String actor = context == null ? null : context.actorName();
        return actor == null ? "" : actor;
    }

    /**
     * 写完之后的库内快照，只为补齐 {@code versionNo}：{@link SkillStore} 的写签名返回摘要行，
     * 而摘要不带版本序号。读失败只丢一个审计字段，不能把已经提交的写变成 5xx。
     */
    private SkillDetail persisted(String skillId) {
        try {
            return store.detail(skillId);
        } catch (RuntimeException e) {
            log.warn("[Skill] 审计快照读取失败 skill={}：{}", skillId, e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> meta(SkillSummary skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.contentLength());
        meta.put("fileCount", skill.fileCount());
        putVersion(meta, skill.revision(), 0);
        return meta;
    }

    private static Map<String, Object> filesMeta(List<SkillFilePackage.Summary> files) {
        List<SkillFilePackage.Summary> safe = files == null ? List.of() : files;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("count", safe.size());
        meta.put(
                "files",
                safe.stream()
                        .map(
                                file -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("path", file.path());
                                    item.put("size", file.size());
                                    item.put("kind", file.kind());
                                    return item;
                                })
                        .toList());
        return meta;
    }

    private static Map<String, Object> meta(SkillDetail skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.content().length());
        meta.put("executionEnabled", skill.executionEnabled());
        putVersion(meta, skill.revision(), skill.versionNo());
        return meta;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** {@code {*path}} 会把剩余路径连同前导斜杠一起捕获，这里还原成仓库使用的相对路径。 */
    private static String relativePath(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path == null ? "" : path;
    }

    /** {@code If-None-Match} 可以是 {@code *}、单个指纹或逗号分隔的列表，任一命中即 304。 */
    private static boolean etagMatches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String want = normalizeEtag(etag);
        for (String candidate : header.split(",")) {
            String value = normalizeEtag(candidate);
            if (value.equals("*") || value.equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉弱校验标记与引号再比：回传形式由客户端决定，不该要求它和我们的写法逐字节一致。 */
    private static String normalizeEtag(String value) {
        String text = value.trim();
        if (text.regionMatches(true, 0, "W/", 0, 2)) {
            text = text.substring(2).trim();
        }
        return text.replace("\"", "");
    }

    /**
     * 新增与编辑共用请求体；编辑时路径 id 优先。
     *
     * @param changeNote 只对编辑生效：写进本次落库的版本行（§9.2）。新建的第一版没有「变更」可言，
     *     传了也忽略，因此前端只在保存已有技能时展示这个输入框。
     * @param templateId 新建时可选：正文来自模板但用户已在编辑器里改过，创建后仍记录模板血缘，
     *     「最近使用的模板」才不会因为一次编辑丢记。未知模板按参数错误拒绝。
     */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillInput(
            String id,
            String description,
            String content,
            Boolean executionEnabled,
            String changeNote,
            String templateId) {

        public SkillInput(
                String id,
                String description,
                String content,
                Boolean executionEnabled,
                String changeNote) {
            this(id, description, content, executionEnabled, changeNote, null);
        }
    }

    /** 文件包全量替换请求体。 */
    public record FileInput(String path, String content) {
    }

    public record FilesInput(List<FileInput> files) {
    }

    /** Git 导入请求体；dryRun 为 true 时只解析预览。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record GitImportInput(
            String url, String ref, String id, Boolean overwrite, Boolean dryRun) {
    }

    /** 模板新建请求体：{@code description} 为空时取模板描述。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record FromTemplateInput(
            String templateId, String id, String description, Boolean executionEnabled) {
    }

    /** 技能数据初始化请求体；overwrite=true 表示清空现有数据重建。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record DataInitInput(Boolean overwrite) {
    }
}
