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
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillInitRequirements;
import io.agentscope.examples.payroll.skill.SkillMetrics;
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
import org.springframework.web.multipart.MultipartFile;

/** 技能管理接口：直接读写 Nacos 配置 {@code <skill-id>.md}，写操作需要 skill:manage 权限。 */
@RestController
@RequestMapping("/api/admin/skills")
public class SkillAdminController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final NacosSkillRegistry registry;
    private final NacosAgentRegistry agentRegistry;
    private final AuditService auditService;
    private final SkillImportService importService;
    private final SandboxGateway gateway;
    /** 已提交但尚未观测到终态的初始化，用于异步补记 skill.init.finish。 */
    private final Map<String, Long> pendingInit = new ConcurrentHashMap<>();

    @Autowired
    public SkillAdminController(
            NacosSkillRegistry registry,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService) {
        this(
                registry,
                agentRegistry,
                auditService,
                importService,
                new SandboxClient(SkillExecutionSettings.fromEnvironment()));
    }

    SkillAdminController(
            NacosSkillRegistry registry,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            SkillImportService importService,
            SandboxGateway gateway) {
        this.registry = registry;
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
        List<SkillSummary> all = registry.list();
        List<SkillSummary> matched = sortSummaries(filterSummaries(all, keyword, source), sort);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long fromLong = (long) (pageNo - 1) * pageSize;
        int from = (int) Math.min(fromLong, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writable", registry.writable());
        result.put("location", registry.location());
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
        return ApiResponse.ok(registry.detail(id));
    }

    /** 读取单个参考文件正文，仅供管理台编辑；路径越界由注册表拒绝。 */
    @GetMapping("/{id}/files/{*path}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse file(@PathVariable String id, @PathVariable String path) {
        String relative = relativePath(path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("path", relative);
        result.put("content", registry.fileContent(id, relative));
        return ApiResponse.ok(result);
    }

    /** 全量替换文件包；空列表等于删除文件包。 */
    @PutMapping("/{id}/files")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse replaceFiles(@PathVariable String id, @RequestBody FilesInput input) {
        List<SkillFilePackage.SkillFile> files = input == null || input.files() == null
                ? List.of()
                : input.files().stream()
                        .map(file -> new SkillFilePackage.SkillFile(file.path(), file.content()))
                        .toList();
        List<SkillFilePackage.Summary> before = registry.files(id);
        List<SkillFilePackage.Summary> after = registry.replaceFiles(id, files);
        auditService.record(
                "skill.files.update", "skill", id, filesMeta(before), filesMeta(after));
        return ApiResponse.ok(filesMeta(after));
    }

    @DeleteMapping("/{id}/files/{*path}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse deleteFile(@PathVariable String id, @PathVariable String path) {
        String relative = relativePath(path);
        registry.deleteFile(id, relative);
        auditService.record(
                "skill.files.delete",
                "skill",
                id,
                Map.of("path", relative),
                Map.of("deleted", relative));
        return ApiResponse.ok(Map.of("deleted", relative));
    }

    @PostMapping
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse create(@RequestBody SkillInput input) {
        SkillSummary saved =
                registry.create(
                        input.id(),
                        input.description(),
                        input.content(),
                        Boolean.TRUE.equals(input.executionEnabled()));
        auditService.record("skill.create", "skill", saved.id(), null, meta(saved));
        return ApiResponse.ok(saved);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse update(@PathVariable String id, @RequestBody SkillInput input) {
        SkillDetail before = registry.detail(id);
        SkillSummary saved =
                registry.update(
                        id,
                        input.description(),
                        input.content(),
                        Boolean.TRUE.equals(input.executionEnabled()));
        auditService.record("skill.update", "skill", saved.id(), meta(before), meta(saved));
        return ApiResponse.ok(saved);
    }

    /** 从 Git 仓库导入目录式技能项目；dryRun 只解析并返回预览，不写 Nacos。 */
    @PostMapping("/import/git")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse importGit(@RequestBody GitImportInput input) {
        if (input == null) {
            throw new PayrollException("请求体不能为空");
        }
        SkillImportService.ImportResult result =
                importService.importGit(
                        input.url(),
                        input.ref(),
                        input.id(),
                        Boolean.TRUE.equals(input.overwrite()),
                        Boolean.TRUE.equals(input.dryRun()));
        if (!result.dryRun()) {
            Map<String, Object> after = importMeta(result);
            after.put("source", "git");
            after.put("url", input.url());
            after.put("ref", input.ref());
            auditService.record("skill.import", "skill", result.id(), null, after);
        }
        return ApiResponse.ok(result);
    }

    /** 从 ZIP 包导入目录式技能项目；dryRun 只解析并返回预览，不写 Nacos。 */
    @PostMapping("/import/zip")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse importZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) Boolean overwrite,
            @RequestParam(required = false) Boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new PayrollException("请选择要导入的 ZIP 文件");
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
                            Boolean.TRUE.equals(dryRun));
            if (!result.dryRun()) {
                Map<String, Object> after = importMeta(result);
                after.put("source", "zip");
                after.put("fileName", file.getOriginalFilename());
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
        SkillDetail detail = registry.detail(id);
        String setupPath = setupPathOf(detail);
        SandboxGateway.SkillData state = queryData(id);
        recordFinish(id, state);
        return ApiResponse.ok(dataMap(id, setupPath, state));
    }

    /** 提交初始化；数据已就绪时需要 overwrite=true 才会清空重建。 */
    @PostMapping("/{id}/data/init")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse initData(
            @PathVariable String id, @RequestBody(required = false) DataInitInput input) {
        SkillDetail detail = registry.detail(id);
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
        auditService.record(
                "skill.init.start", "skill", id, stateMeta(current), after);
        return ApiResponse.ok(dataMap(id, setupPath, submitted));
    }

    /** 删除技能数据工作区；初始化进行中会被沙箱拒绝。 */
    @DeleteMapping("/{id}/data")
    @RequirePermission(Permissions.SKILL_MANAGE)
    public ApiResponse deleteData(@PathVariable String id) {
        SkillDetail detail = registry.detail(id);
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
        auditService.record(
                "skill.data.delete", "skill", id, stateMeta(before), stateMeta(deleted));
        return ApiResponse.ok(dataMap(id, setupPathOf(detail), deleted));
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

    private void recordFinish(String id, SandboxGateway.SkillData state) {
        if (state == null) {
            return;
        }
        boolean terminal =
                SandboxGateway.SkillData.READY.equals(state.status())
                        || SandboxGateway.SkillData.FAILED.equals(state.status());
        if (!terminal || pendingInit.remove(id) == null) {
            return;
        }
        auditService.record("skill.init.finish", "skill", id, null, stateMeta(state));
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
            contents.put(file.path(), registry.fileContent(detail.id(), file.path()));
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

    private static Map<String, Object> meta(SkillSummary skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.contentLength());
        meta.put("fileCount", skill.fileCount());
        return meta;
    }

    private static Map<String, Object> filesMeta(List<SkillFilePackage.Summary> files) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("count", files.size());
        meta.put(
                "files",
                files.stream()
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
        return meta;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** {@code {*path}} 会把剩余路径连同前导斜杠一起捕获，这里还原成仓库使用的相对路径。 */
    private static String relativePath(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path == null ? "" : path;
    }

    /** 新增与编辑共用请求体；编辑时路径 id 优先。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillInput(
            String id, String description, String content, Boolean executionEnabled) {
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
