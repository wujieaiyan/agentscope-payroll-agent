package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.SandboxClient;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.SkillDetail;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillIds;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillInitRequirements;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillMetrics;
import io.agentscope.examples.payroll.skill.SkillStore;
import io.agentscope.examples.payroll.skill.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillVersion;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.Permissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

    private static final Logger log = LoggerFactory.getLogger(SkillAdminController.class);

    private final SkillStore store;
    private final NacosAgentRegistry agentRegistry;
    private final AuditService auditService;
    private final SkillImportService importService;
    private final SandboxGateway gateway;
    /** 已提交但尚未观测到终态的初始化，用于异步补记 skill.init.finish。 */
    private final Map<String, Long> pendingInit = new ConcurrentHashMap<>();

    @Autowired
    public SkillAdminController(
            SkillStore store,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService) {
        this(
                store,
                agentRegistry,
                auditService,
                importService,
                new SandboxClient(SkillExecutionSettings.fromEnvironment()));
    }

    SkillAdminController(
            SkillStore store,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService,
            SandboxGateway gateway) {
        this.store = store;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.importService = importService;
        this.gateway =
                gateway == null
                        ? new SandboxClient(SkillExecutionSettings.fromEnvironment())
                        : gateway;
    }

    /**
     * 列表同时返回存储状态与规模指标；支持关键字/来源过滤、排序与分页。
     * 默认每页 50 条，最多 200 条，避免技能规模化后一次渲染全部条目。
     */
    @GetMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sort) {
        List<SkillSummary> all = store.list();
        List<SkillSummary> matched = sortSummaries(filterSummaries(all, keyword, source), sort);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long fromLong = (long) (pageNo - 1) * pageSize;
        int from = (int) Math.min(fromLong, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writable", store.writable());
        result.put("location", store.location());
        result.put("total", matched.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("stats", scaleStats(all));
        result.put("skills", matched.subList(from, to));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse detail(@PathVariable String id) {
        return ApiResponse.ok(store.detail(id));
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

    @PostMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse create(@RequestBody SkillInput input) {
        SkillSummary saved =
                store.create(
                        input.id(),
                        input.description(),
                        input.content(),
                        Boolean.TRUE.equals(input.executionEnabled()),
                        operator());
        Map<String, Object> after = meta(saved);
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
        SkillImportService.ImportResult result =
                importService.importGit(
                        input.url(),
                        input.ref(),
                        input.id(),
                        Boolean.TRUE.equals(input.overwrite()),
                        dryRun,
                        revision,
                        operator());
        if (!result.dryRun()) {
            Map<String, Object> after = importMeta(result);
            after.put("source", "git");
            after.put("url", input.url());
            after.put("ref", input.ref());
            putVersion(after, persisted(result.id()));
            auditService.record("skill.import", "skill", result.id(), null, after);
        }
        return ApiResponse.ok(result);
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
            SkillImportService.ImportResult result =
                    importService.importZip(
                            temp,
                            id,
                            Boolean.TRUE.equals(overwrite),
                            Boolean.TRUE.equals(dryRun),
                            revision,
                            operator());
            if (!result.dryRun()) {
                Map<String, Object> after = importMeta(result);
                after.put("source", "zip");
                after.put("fileName", file.getOriginalFilename());
                putVersion(after, persisted(result.id()));
                auditService.record("skill.import", "skill", result.id(), null, after);
            }
            return ApiResponse.ok(result);
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
        return ApiResponse.ok(dataMap(id, setupPath, state));
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
        Map<String, Object> beforeMeta = stateMeta(before);
        Map<String, Object> afterMeta = stateMeta(deleted);
        putVersion(beforeMeta, detail);
        putVersion(afterMeta, detail);
        auditService.record("skill.data.delete", "skill", id, beforeMeta, afterMeta);
        return ApiResponse.ok(dataMap(id, setupPathOf(detail), deleted));
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
        auditService.record("skill.delete", "skill", id, meta(before), Map.of("deleted", id));
        return ApiResponse.ok(Map.of("deleted", id));
    }

    private static List<SkillSummary> filterSummaries(
            List<SkillSummary> skills, String keyword, String source) {
        String needle =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim().toLowerCase(Locale.ROOT);
        String sourceFilter = source == null || source.isBlank() ? null : source.trim();
        return skills.stream()
                .filter(skill -> sourceFilter == null
                        || (skill.source() != null && skill.source().equalsIgnoreCase(sourceFilter)))
                .filter(skill -> needle == null
                        || contains(skill.id(), needle)
                        || contains(skill.description(), needle))
                .toList();
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

    /** 规模指标：技能总数、已绑定智能体数、被绑定技能数、正文总字节、目录构建耗时、缓存命中率。 */
    private Map<String, Object> scaleStats(List<SkillSummary> all) {
        Set<String> known = all.stream().map(SkillSummary::id).collect(Collectors.toSet());
        List<AgentDefinition> agents = agentRegistry.list();
        Set<String> bound =
                agents.stream()
                        .filter(agent -> agent.skillIds() != null)
                        .flatMap(agent -> agent.skillIds().stream())
                        .filter(known::contains)
                        .collect(Collectors.toSet());
        long boundAgents =
                agents.stream()
                        .filter(agent -> agent.skillIds() != null && !agent.skillIds().isEmpty())
                        .count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSkills", all.size());
        stats.put("boundAgents", boundAgents);
        stats.put("boundSkills", bound.size());
        stats.put("contentBytes", all.stream().mapToLong(SkillSummary::contentLength).sum());
        stats.put("catalogBuildMillis", SkillMetrics.lastCatalogBuildMillis());
        stats.put("cacheHitRatio", SkillMetrics.contentCacheHitRatio());
        return stats;
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

    private static String setupCommand(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("setup");
        return raw == null ? null : String.valueOf(raw);
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
     */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillInput(
            String id,
            String description,
            String content,
            Boolean executionEnabled,
            String changeNote) {
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

    /** 技能数据初始化请求体；overwrite=true 表示清空现有数据重建。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record DataInitInput(Boolean overwrite) {
    }
}
