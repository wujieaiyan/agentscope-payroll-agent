package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能管理注册表：Nacos 中 dataId 为 {@code <skill-id>.md} 的配置就是可编辑技能，
 * 伴生配置 {@code <skill-id>.files.json} 存放参考文件与脚本。
 *
 * <p>列表把 Nacos 技能与三个内置角色技能合并：同名 Nacos 技能覆盖内置版本，
 * 删除覆盖后自动回退内置提示词。读路径带 TTL 缓存，写操作直接发布/删除 Nacos 配置。
 */
public final class NacosSkillRegistry {

    public static final String DATA_ID_SUFFIX = ".md";
    public static final String SOURCE_NACOS = "nacos";
    public static final String SOURCE_BUILTIN = "builtin";

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRegistry.class);
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final int MAX_ID_LENGTH = 64;

    /** 管理台列表项；contentLength 为去掉 frontmatter 后的正文字数。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillSummary(
            String id,
            String description,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            int contentLength,
            int fileCount) {
    }

    /** 编辑详情：content 为正文（不含 frontmatter），保存时由服务端重新生成 frontmatter。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillDetail(
            String id,
            String description,
            String content,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            List<SkillFilePackage.Summary> files,
            boolean executionEnabled,
            Map<String, Object> metadata) {

        public SkillDetail(
                String id,
                String description,
                String content,
                String source,
                boolean builtin,
                boolean overridden,
                long updatedAt,
                List<SkillFilePackage.Summary> files,
                boolean executionEnabled) {
            this(
                    id,
                    description,
                    content,
                    source,
                    builtin,
                    overridden,
                    updatedAt,
                    files,
                    executionEnabled,
                    Map.of());
        }
    }

    private record RemoteSkill(
            String id,
            String raw,
            String description,
            String content,
            long updatedAt,
            List<SkillFilePackage.SkillFile> files,
            boolean executionEnabled,
            Map<String, Object> metadata) {
    }

    private record Snapshot(List<RemoteSkill> remotes, Map<String, RemoteSkill> byId, long fetchedAt) {
    }

    private final NacosSkillSettings settings;
    private final SkillFileSettings fileSettings;
    private final NacosConfigClient client;
    private final long ttlMillis;

    private volatile Snapshot snapshot;

    public NacosSkillRegistry() {
        this(NacosSkillSettings.fromEnvironment(), SkillFileSettings.fromEnvironment());
    }

    NacosSkillRegistry(NacosSkillSettings settings) {
        this(settings, SkillFileSettings.defaults());
    }

    NacosSkillRegistry(NacosSkillSettings settings, SkillFileSettings fileSettings) {
        this.settings = settings;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
    }

    public boolean writable() {
        return settings.enabled();
    }

    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    public List<SkillSummary> list() {
        Snapshot snap = snapshot();
        List<SkillSummary> summaries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : RoleSkills.ids()) {
            RemoteSkill remote = snap.byId().get(id);
            summaries.add(remote == null ? builtinSummary(id) : summary(remote, true));
            seen.add(id);
        }
        snap.remotes().stream()
                .filter(remote -> !seen.contains(remote.id()))
                .sorted(Comparator.comparing(RemoteSkill::id))
                .map(remote -> summary(remote, false))
                .forEach(summaries::add);
        return List.copyOf(summaries);
    }

    public SkillDetail detail(String id) {
        String skillId = normalizeId(id);
        RemoteSkill remote = snapshot().byId().get(skillId);
        boolean builtin = RoleSkills.ids().contains(skillId);
        if (remote != null) {
            return new SkillDetail(
                    skillId,
                    remote.description(),
                    remote.content(),
                    SOURCE_NACOS,
                    builtin,
                    builtin,
                    remote.updatedAt(),
                    summaries(remote.files()),
                    remote.executionEnabled(),
                    remote.metadata());
        }
        if (builtin) {
            AgentSkill skill = RoleSkills.skill(skillId);
            return new SkillDetail(
                    skillId,
                    skill.getDescription(),
                    skill.getSkillContent(),
                    SOURCE_BUILTIN,
                    true,
                    false,
                    0,
                    List.of(),
                    false,
                    Map.of());
        }
        throw new PayrollException("技能不存在：" + skillId);
    }

    /** 管理台文件列表；不存在或 Nacos 不可用时为空。 */
    public List<SkillFilePackage.Summary> files(String id) {
        RemoteSkill remote = snapshot().byId().get(normalizeId(id));
        return remote == null ? List.of() : summaries(remote.files());
    }

    /** 读取单个文件内容；路径越界与不存在分别报错。 */
    public String fileContent(String id, String path) {
        String skillId = normalizeId(id);
        String normalized = SkillFilePackage.normalizePath(path);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            throw new PayrollException("技能不存在或未发布到 Nacos：" + skillId);
        }
        return remote.files().stream()
                .filter(file -> file.path().equals(normalized))
                .findFirst()
                .map(SkillFilePackage.SkillFile::content)
                .orElseThrow(() -> new PayrollException("文件不存在：" + normalized));
    }

    /** 全量替换文件包；空列表等于删除文件包。 */
    public synchronized List<SkillFilePackage.Summary> replaceFiles(
            String id, List<SkillFilePackage.SkillFile> files) {
        requireWritable();
        String skillId = normalizeId(id);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            if (RoleSkills.ids().contains(skillId)) {
                throw new PayrollException("内置技能尚未覆盖到 Nacos，请先保存正文再上传文件");
            }
            throw new PayrollException("技能不存在：" + skillId);
        }
        if (!fileSettings.enabled()) {
            throw new PayrollException(
                    "技能文件包已停用（PAYROLL_SKILL_FILES_ENABLED=false），无法编辑文件");
        }
        List<SkillFilePackage.SkillFile> clean =
                SkillFilePackage.normalizeAll(files, fileSettings);
        String dataId = SkillFilePackage.dataId(skillId);
        boolean saved =
                clean.isEmpty()
                        ? client.remove(dataId)
                        : client.publish(
                                dataId,
                                SkillFilePackage.compose(
                                        SkillFilePackage.mdHash(remote.content()),
                                        remote.metadata(),
                                        clean),
                                "json");
        if (!saved) {
            throw new PayrollException(
                    "写入 Nacos 配置 " + dataId + " 失败，请检查 Nacos 连接与账号权限");
        }
        snapshot = null;
        log.info("[Skill] 已更新技能 {} 的文件包（{} 个文件）", skillId, clean.size());
        return summaries(clean);
    }

    public synchronized boolean deleteFile(String id, String path) {
        requireWritable();
        String skillId = normalizeId(id);
        String normalized = SkillFilePackage.normalizePath(path);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            throw new PayrollException("技能不存在或未发布到 Nacos：" + skillId);
        }
        if (remote.files().stream().noneMatch(file -> file.path().equals(normalized))) {
            throw new PayrollException("文件不存在：" + normalized);
        }
        List<SkillFilePackage.SkillFile> remaining =
                remote.files().stream()
                        .filter(file -> !file.path().equals(normalized))
                        .toList();
        replaceFiles(skillId, remaining);
        return true;
    }

    public synchronized SkillSummary create(String id, String description, String content) {
        return create(id, description, content, false);
    }

    public synchronized SkillSummary create(
            String id, String description, String content, boolean executionEnabled) {
        requireWritable();
        String skillId = normalizeId(id);
        if (exists(skillId)) {
            throw new PayrollException("技能已存在：" + skillId);
        }
        return save(skillId, description, content, executionEnabled);
    }

    public synchronized SkillSummary update(
            String id, String description, String content, boolean executionEnabled) {
        requireWritable();
        String skillId = normalizeId(id);
        if (!exists(skillId)) {
            throw new PayrollException("技能不存在：" + skillId);
        }
        return save(skillId, description, content, executionEnabled);
    }

    /**
     * 导入目录式技能项目：正文与 frontmatter 元信息整体发布，并全量替换文件包。
     *
     * <p>覆盖已有技能时保留执行开关，其余元信息以导入项目为准；内置技能允许被覆盖，
     * 但不允许静默覆盖，必须由调用方显式确认。
     */
    public synchronized SkillSummary importProject(
            SkillProjectParser.ParsedProject project, boolean overwrite) {
        requireWritable();
        if (project == null) {
            throw new PayrollException("技能项目不能为空");
        }
        String skillId = normalizeId(project.id());
        RemoteSkill existing = snapshot().byId().get(skillId);
        boolean builtin = RoleSkills.ids().contains(skillId);
        if ((builtin || existing != null) && !overwrite) {
            throw new PayrollConflictException(
                    "技能已存在：" + skillId + "；覆盖导入需要显式确认");
        }
        String cleanDescription = SkillContentRules.cleanDescription(project.description());
        String cleanContent = SkillContentRules.requireBody(project.body());
        boolean executionEnabled = existing != null && existing.executionEnabled();
        String markdown =
                SkillFrontmatter.compose(
                        skillId,
                        cleanDescription,
                        cleanContent,
                        executionEnabled,
                        project.metadata());
        if (!client.publish(skillId + DATA_ID_SUFFIX, markdown, "markdown")) {
            throw new PayrollException(
                    "写入 Nacos 配置 "
                            + skillId
                            + DATA_ID_SUFFIX
                            + " 失败，请检查 Nacos 连接与账号权限");
        }
        snapshot = null;
        List<SkillFilePackage.SkillFile> files =
                SkillFilePackage.normalizeAll(project.files(), fileSettings);
        String filesDataId = SkillFilePackage.dataId(skillId);
        if (files.isEmpty()) {
            if (existing != null && !existing.files().isEmpty()) {
                try {
                    if (!client.remove(filesDataId)) {
                        log.warn("[Skill] 导入技能 {} 时清理旧文件包失败，已忽略", skillId);
                    }
                } catch (RuntimeException e) {
                    log.warn("[Skill] 导入技能 {} 时清理旧文件包异常，已忽略：{}", skillId, e.getMessage());
                }
            }
        } else if (!fileSettings.enabled()) {
            log.warn("[Skill] 技能文件包已停用，导入 {} 时只发布正文", skillId);
        } else if (!client.publish(
                filesDataId,
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash(cleanContent), project.metadata(), files),
                "json")) {
            throw new PayrollException(
                    "正文已保存，但文件包 "
                            + filesDataId
                            + " 写入失败，请重试或重新发布文件");
        }
        snapshot = null;
        int fileCount = fileSettings.enabled() ? files.size() : 0;
        log.info(
                "[Skill] 已导入技能 {}（{} 字，{} 个文件，覆盖={}）",
                skillId,
                cleanContent.length(),
                fileCount,
                existing != null || builtin);
        return new SkillSummary(
                skillId,
                cleanDescription,
                SOURCE_NACOS,
                builtin,
                builtin,
                System.currentTimeMillis(),
                cleanContent.length(),
                fileCount);
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        String skillId = normalizeId(id);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            if (RoleSkills.ids().contains(skillId)) {
                throw new PayrollException("内置技能没有 Nacos 覆盖版本，不能删除");
            }
            throw new PayrollException("技能不存在：" + skillId);
        }
        if (!client.remove(skillId + DATA_ID_SUFFIX)) {
            throw new PayrollException(
                    "删除 Nacos 配置 "
                            + skillId
                            + DATA_ID_SUFFIX
                            + " 失败，请检查 Nacos 连接与账号权限");
        }
        // 文件包删除失败不影响正文回退；残留文件包在下次保存正文时重新绑定哈希。
        try {
            if (!client.remove(SkillFilePackage.dataId(skillId))) {
                log.warn("[Skill] 删除技能 {} 的文件包失败，已忽略", skillId);
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 删除技能 {} 的文件包异常，已忽略：{}", skillId, e.getMessage());
        }
        snapshot = null;
        log.info("[Skill] 已删除 Nacos 技能 {}", skillId);
        return true;
    }

    private SkillSummary save(
            String skillId, String description, String content, boolean executionEnabled) {
        String cleanDescription = SkillContentRules.cleanDescription(description);
        String cleanContent = SkillContentRules.requireBody(content);
        RemoteSkill existing = snapshot().byId().get(skillId);
        String markdown =
                SkillFrontmatter.compose(
                        skillId,
                        cleanDescription,
                        cleanContent,
                        executionEnabled,
                        existing == null ? Map.of() : existing.metadata());
        if (!client.publish(skillId + DATA_ID_SUFFIX, markdown, "markdown")) {
            throw new PayrollException(
                    "写入 Nacos 配置 "
                            + skillId
                            + DATA_ID_SUFFIX
                            + " 失败，请检查 Nacos 连接与账号权限");
        }
        snapshot = null;
        // 正文变了，文件包必须重绑 mdHash，否则读取时会被判定为版本错配而忽略。
        if (fileSettings.enabled() && existing != null && !existing.files().isEmpty()) {
            String filesDataId = SkillFilePackage.dataId(skillId);
            boolean rebound =
                    client.publish(
                            filesDataId,
                            SkillFilePackage.compose(
                                    SkillFilePackage.mdHash(cleanContent),
                                    existing.metadata(),
                                    existing.files()),
                            "json");
            if (!rebound) {
                throw new PayrollException(
                        "正文已保存，但文件包 "
                                + filesDataId
                                + " 重绑失败，请重新保存或重新发布文件");
            }
        }
        boolean builtin = RoleSkills.ids().contains(skillId);
        int fileCount = existing == null ? 0 : existing.files().size();
        log.info(
                "[Skill] 已保存 Nacos 技能 {}（{} 字，{} 个文件）",
                skillId,
                cleanContent.length(),
                fileCount);
        return new SkillSummary(
                skillId,
                cleanDescription,
                SOURCE_NACOS,
                builtin,
                builtin,
                System.currentTimeMillis(),
                cleanContent.length(),
                fileCount);
    }

    private boolean exists(String id) {
        return RoleSkills.ids().contains(id) || snapshot().byId().containsKey(id);
    }

    private Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (current != null && now - current.fetchedAt() < ttlMillis) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && now - current.fetchedAt() < ttlMillis) {
                return current;
            }
            current = load();
            snapshot = current;
            return current;
        }
    }

    private Snapshot load() {
        if (!settings.enabled()) {
            return new Snapshot(List.of(), Map.of(), System.currentTimeMillis());
        }
        Map<String, RemoteSkill> byId = new LinkedHashMap<>();
        for (NacosConfigClient.ConfigEntry entry : client.list("*" + DATA_ID_SUFFIX)) {
            String dataId = entry.dataId();
            if (!dataId.startsWith(".") && !dataId.endsWith(DATA_ID_SUFFIX)) {
                continue;
            }
            String id = dataId.substring(0, dataId.length() - DATA_ID_SUFFIX.length());
            if (!isValidId(id)) {
                continue;
            }
            byId.put(id, parseRemote(id, entry));
        }
        attachPackages(byId);
        log.info("[Skill] 从 Nacos 加载 {} 个技能配置", byId.size());
        return new Snapshot(List.copyOf(byId.values()), Map.copyOf(byId), System.currentTimeMillis());
    }

    /** 文件包与正文分两次列表读取；哈希不符或校验失败时只丢文件，不影响正文。 */
    private void attachPackages(Map<String, RemoteSkill> byId) {
        if (!fileSettings.enabled() || byId.isEmpty()) {
            return;
        }
        Map<String, SkillFilePackage.Parsed> packages = new LinkedHashMap<>();
        for (NacosConfigClient.ConfigEntry entry :
                client.list("*" + SkillFilePackage.DATA_ID_SUFFIX)) {
            String dataId = entry.dataId();
            if (dataId == null || !dataId.endsWith(SkillFilePackage.DATA_ID_SUFFIX)) {
                continue;
            }
            String id =
                    dataId.substring(
                            0, dataId.length() - SkillFilePackage.DATA_ID_SUFFIX.length());
            if (!isValidId(id)) {
                continue;
            }
            Optional<SkillFilePackage.Parsed> parsed = SkillFilePackage.parse(entry.content());
            if (parsed.isEmpty()) {
                log.warn("[Skill] 技能 {} 的文件包 JSON 无效或版本不支持，已忽略", id);
                continue;
            }
            packages.put(id, parsed.get());
        }
        for (Map.Entry<String, RemoteSkill> entry : byId.entrySet()) {
            SkillFilePackage.Parsed parsed = packages.get(entry.getKey());
            if (parsed == null) {
                continue;
            }
            RemoteSkill remote = entry.getValue();
            if (!parsed.mdHash().equals(SkillFilePackage.mdHash(remote.content()))) {
                log.warn(
                        "[Skill] 技能 {} 的文件包与正文哈希不一致，已忽略文件（请保存正文后重新发布文件）",
                        remote.id());
                continue;
            }
            try {
                List<SkillFilePackage.SkillFile> files =
                        SkillFilePackage.normalizeAll(parsed.files(), fileSettings);
                entry.setValue(
                        new RemoteSkill(
                                remote.id(),
                                remote.raw(),
                                remote.description(),
                                remote.content(),
                                remote.updatedAt(),
                                files,
                                remote.executionEnabled(),
                                remote.metadata()));
            } catch (RuntimeException e) {
                log.warn("[Skill] 技能 {} 的文件包校验失败，已忽略文件：{}", remote.id(), e.getMessage());
            }
        }
    }

    private RemoteSkill parseRemote(String id, NacosConfigClient.ConfigEntry entry) {
        String raw = entry.content();
        String content = raw;
        String description = "";
        boolean executionEnabled = false;
        Map<String, Object> metadata = Map.of();
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(raw);
            if (parsed != null) {
                if (parsed.hasFrontmatter()
                        && parsed.getContent() != null
                        && !parsed.getContent().isBlank()) {
                    content = parsed.getContent();
                }
                if (parsed.getMetadata() != null) {
                    metadata = new LinkedHashMap<>(parsed.getMetadata());
                    Object value = parsed.getMetadata().get("description");
                    if (value != null) {
                        description = value.toString().trim();
                    }
                    executionEnabled = SkillExecutions.executionEnabled(parsed.getMetadata());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 解析 Nacos 技能 {} 失败，按纯文本使用：{}", id, e.getMessage());
        }
        if (description.isBlank() && RoleSkills.ids().contains(id)) {
            description = RoleSkills.description(id);
        }
        return new RemoteSkill(
                id,
                raw,
                description,
                content,
                entry.updatedAt(),
                List.of(),
                executionEnabled,
                metadata);
    }

    private static SkillSummary summary(RemoteSkill remote, boolean builtin) {
        return new SkillSummary(
                remote.id(),
                remote.description(),
                SOURCE_NACOS,
                builtin,
                builtin,
                remote.updatedAt(),
                remote.content().length(),
                remote.files().size());
    }

    private static SkillSummary builtinSummary(String id) {
        AgentSkill skill = RoleSkills.skill(id);
        return new SkillSummary(
                id,
                skill.getDescription(),
                SOURCE_BUILTIN,
                true,
                false,
                0,
                skill.getSkillContent().length(),
                0);
    }

    private static List<SkillFilePackage.Summary> summaries(
            List<SkillFilePackage.SkillFile> files) {
        return files.stream().map(SkillFilePackage::summary).toList();
    }

    private static String normalizeId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.isBlank()) {
            throw new PayrollException("技能 ID 必填");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new PayrollException("技能 ID 不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        if (!isValidId(value)) {
            throw new PayrollException(
                    "技能 ID 只能包含小写字母、数字和短横线，且以小写字母开头（2–64 位）");
        }
        return value;
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法管理技能");
        }
    }
}
