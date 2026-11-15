package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
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
}
