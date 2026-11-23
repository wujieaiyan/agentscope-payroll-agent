package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能管理注册表：Nacos 中 dataId 为 {@code <skill-id>.md} 的配置就是可编辑技能，
 * 伴生配置 {@code <skill-id>.files.json} 存放参考文件与脚本。
 *
 * <p>列表把 Nacos 技能与三个内置角色技能合并：同名 Nacos 技能覆盖内置版本，
 * 删除覆盖后自动回退内置提示词。读路径带 TTL 缓存，写操作直接发布/删除 Nacos 配置。
 */
public final class NacosSkillRegistry implements SkillStore {

    public static final String DATA_ID_SUFFIX = ".md";
    public static final String SOURCE_NACOS = "nacos";
    public static final String SOURCE_BUILTIN = "builtin";

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRegistry.class);

    private record RemoteSkill(
            String id,
            String raw,
            String description,
            String content,
            long updatedAt,
            boolean executionEnabled,
            Map<String, Object> metadata) {
    }

    private record Snapshot(List<RemoteSkill> remotes, Map<String, RemoteSkill> byId, long fetchedAt) {
    }

    /** 文件包按需加载的缓存项；mdHash 用于正文变化时立即失效，bytes 参与总字节上限。 */
    private record CachedFiles(long fetchedAt, String mdHash, long bytes, List<SkillFilePackage.SkillFile> files) {
    }

    /** 访问序 LRU：条数与正文总字节双上限，超限从最久未访问开始淘汰。 */
    private static final class FileCache {
        private final int maxEntries;
        private final long maxBytes;
        private final Map<String, CachedFiles> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        private FileCache(int maxEntries, long maxBytes) {
            this.maxEntries = Math.max(1, maxEntries);
            this.maxBytes = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        }

        synchronized CachedFiles get(String skillId) {
            return entries.get(skillId);
        }

        synchronized void put(String skillId, CachedFiles value) {
            CachedFiles previous = entries.put(skillId, value);
            if (previous != null) {
                bytes -= previous.bytes();
            }
            bytes += value.bytes();
            var iterator = entries.entrySet().iterator();
            while ((entries.size() > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
                CachedFiles evicted = iterator.next().getValue();
                bytes -= evicted.bytes();
                iterator.remove();
            }
        }

        synchronized void remove(String skillId) {
            CachedFiles removed = entries.remove(skillId);
            if (removed != null) {
                bytes -= removed.bytes();
            }
        }

        synchronized void retainAll(Set<String> skillIds) {
            entries.entrySet().removeIf(entry -> {
                if (skillIds.contains(entry.getKey())) {
                    return false;
                }
                bytes -= entry.getValue().bytes();
                return true;
            });
        }
    }

    private static final int DEFAULT_FILE_CACHE_MAX_ENTRIES = 128;
    private static final long DEFAULT_FILE_CACHE_MAX_BYTES = 32L * 1024 * 1024;

    private final NacosSkillSettings settings;
    private final SkillFileSettings fileSettings;
    private final NacosConfigClient client;
    private final long ttlMillis;

    private volatile Snapshot snapshot;
    private volatile long lastSuccessfulListAt;
    private final FileCache fileCache;

    public NacosSkillRegistry() {
        this(
                NacosSkillSettings.fromEnvironment(),
                SkillFileSettings.fromEnvironment(),
                envInt("PAYROLL_SKILL_FILE_CACHE_MAX_ENTRIES", DEFAULT_FILE_CACHE_MAX_ENTRIES),
                envLong("PAYROLL_SKILL_FILE_CACHE_MAX_BYTES", DEFAULT_FILE_CACHE_MAX_BYTES));
    }

    /** Spring 装配入口：file-cache 上限来自 application-common.yml，便于统一调参与回滚。 */
    public NacosSkillRegistry(int fileCacheMaxEntries, long fileCacheMaxBytes) {
        this(
                NacosSkillSettings.fromEnvironment(),
                SkillFileSettings.fromEnvironment(),
                fileCacheMaxEntries,
                fileCacheMaxBytes);
    }

    public NacosSkillRegistry(NacosSkillSettings settings) {
        this(settings, SkillFileSettings.defaults());
    }

    NacosSkillRegistry(NacosSkillSettings settings, SkillFileSettings fileSettings) {
        this(settings, fileSettings, DEFAULT_FILE_CACHE_MAX_ENTRIES, DEFAULT_FILE_CACHE_MAX_BYTES);
    }

    NacosSkillRegistry(
            NacosSkillSettings settings,
            SkillFileSettings fileSettings,
            int fileCacheMaxEntries,
            long fileCacheMaxBytes) {
        this.settings = settings;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
        this.fileCache = new FileCache(fileCacheMaxEntries, fileCacheMaxBytes);
    }

    private static int envInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数，当前值：" + value);
        }
    }

    private static long envLong(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数，当前值：" + value);
        }
    }

    @Override
    public boolean writable() {
        return settings.enabled();
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public long lastSuccessfulListAt() {
        return lastSuccessfulListAt;
    }

    @Override
    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    @Override
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

    @Override
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
                    summaries(filesOf(remote)),
                    remote.executionEnabled(),
                    remote.metadata(),
                    "",
                    0);
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
                    Map.of(),
                    "",
                    0);
        }
        throw new PayrollException("技能不存在：" + skillId);
    }

    /** 管理台文件列表；不存在或 Nacos 不可用时为空。 */
    @Override
    public List<SkillFilePackage.Summary> files(String id) {
        RemoteSkill remote = snapshot().byId().get(normalizeId(id));
        return remote == null ? List.of() : summaries(filesOf(remote));
    }

    /** 读取单个文件内容；路径越界与不存在分别报错。 */
    @Override
    public String fileContent(String id, String path) {
        String skillId = normalizeId(id);
        String normalized = SkillFilePackage.normalizePath(path);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            throw new PayrollException("技能不存在或未发布到 Nacos：" + skillId);
        }
        return filesOf(remote).stream()
                .filter(file -> file.path().equals(normalized))
                .findFirst()
                .map(SkillFilePackage.SkillFile::content)
                .orElseThrow(() -> new PayrollException("文件不存在：" + normalized));
    }

    /** 全量替换文件包；空列表等于删除文件包。 */
    @Override
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
        String mdHash = SkillFilePackage.mdHash(remote.content());
        boolean saved =
                clean.isEmpty()
                        ? client.remove(dataId)
                        : client.publish(
                                dataId,
                                SkillFilePackage.compose(mdHash, remote.metadata(), clean),
                                "json");
        if (!saved) {
            throw new PayrollException(
                    "写入 Nacos 配置 " + dataId + " 失败，请检查 Nacos 连接与账号权限");
        }
        dropSnapshot();
        fileCache.put(
                skillId,
                new CachedFiles(
                        System.currentTimeMillis(), mdHash, fileBytes(clean), List.copyOf(clean)));
        log.info("[Skill] 已更新技能 {} 的文件包（{} 个文件）", skillId, clean.size());
        return summaries(clean);
    }

    @Override
    public synchronized boolean deleteFile(String id, String path) {
        requireWritable();
        String skillId = normalizeId(id);
        String normalized = SkillFilePackage.normalizePath(path);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            throw new PayrollException("技能不存在或未发布到 Nacos：" + skillId);
        }
        if (filesOf(remote).stream().noneMatch(file -> file.path().equals(normalized))) {
            throw new PayrollException("文件不存在：" + normalized);
        }
        List<SkillFilePackage.SkillFile> remaining =
                filesOf(remote).stream()
                        .filter(file -> !file.path().equals(normalized))
                        .toList();
        replaceFiles(skillId, remaining);
        return true;
    }

    public synchronized SkillSummary create(String id, String description, String content) {
        return create(id, description, content, false);
    }

    @Override
    public synchronized SkillSummary create(
            String id, String description, String content, boolean executionEnabled) {
        requireWritable();
        String skillId = normalizeId(id);
        if (exists(skillId)) {
            throw new PayrollException("技能已存在：" + skillId);
        }
        return save(skillId, description, content, executionEnabled);
    }

    @Override
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
    @Override
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
        dropSnapshot();
        List<SkillFilePackage.SkillFile> files =
                SkillFilePackage.normalizeAll(project.files(), fileSettings);
        String filesDataId = SkillFilePackage.dataId(skillId);
        if (files.isEmpty()) {
            if (existing != null && !filesOf(existing).isEmpty()) {
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
        dropSnapshot();
        fileCache.remove(skillId);
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
                fileCount,
                "");
    }

    @Override
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
        dropSnapshot();
        fileCache.remove(skillId);
        log.info("[Skill] 已删除 Nacos 技能 {}", skillId);
        return true;
    }

    /**
     * 写成功后同时丢弃注册表快照与运行期技能仓库快照：只清自己的话，本节点仍要等目录
     * TTL（默认 60s）才让新正文/新文件进入智能体缓存指纹。
     */
    private void dropSnapshot() {
        snapshot = null;
        SkillRepositories.invalidateShared();
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
        dropSnapshot();
        // 正文变了，文件包必须重绑 mdHash，否则读取时会被判定为版本错配而忽略。
        List<SkillFilePackage.SkillFile> existingFiles =
                existing == null ? List.of() : filesOf(existing);
        if (fileSettings.enabled() && existing != null && !existingFiles.isEmpty()) {
            String filesDataId = SkillFilePackage.dataId(skillId);
            boolean rebound =
                    client.publish(
                            filesDataId,
                            SkillFilePackage.compose(
                                    SkillFilePackage.mdHash(cleanContent),
                                    existing.metadata(),
                                    existingFiles),
                            "json");
            if (!rebound) {
                throw new PayrollException(
                        "正文已保存，但文件包 "
                                + filesDataId
                                + " 重绑失败，请重新保存或重新发布文件");
            }
        }
        fileCache.remove(skillId);
        boolean builtin = RoleSkills.ids().contains(skillId);
        int fileCount = existingFiles.size();
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
                fileCount,
                "");
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
            current = load(current);
            snapshot = current;
            return current;
        }
    }

    private Snapshot load(Snapshot previous) {
        if (!settings.enabled()) {
            return new Snapshot(List.of(), Map.of(), System.currentTimeMillis());
        }
        long started = System.nanoTime();
        try {
            NacosConfigClient.ListResult listed = client.listResult("*" + DATA_ID_SUFFIX);
            if (!listed.ok()) {
                // 失败也写入 fetchedAt，避免 Nacos 故障期间每次请求都重试。
                if (previous != null && !previous.remotes().isEmpty()) {
                    log.warn(
                            "[Skill] 列出 Nacos 技能失败，沿用上次快照（{} 个）：{}",
                            previous.remotes().size(),
                            listed.warning());
                    return new Snapshot(
                            previous.remotes(), previous.byId(), System.currentTimeMillis());
                }
                log.warn("[Skill] 列出 Nacos 技能失败且无历史快照，仅保留内置技能：{}", listed.warning());
                return new Snapshot(List.of(), Map.of(), System.currentTimeMillis());
            }
            Map<String, RemoteSkill> byId = new LinkedHashMap<>();
            for (NacosConfigClient.ConfigEntry entry : listed.entries()) {
                String dataId = entry.dataId();
                if (!dataId.startsWith(".") && !dataId.endsWith(DATA_ID_SUFFIX)) {
                    continue;
                }
                String id = dataId.substring(0, dataId.length() - DATA_ID_SUFFIX.length());
                if (!SkillIds.isValid(id)) {
                    continue;
                }
                byId.put(id, parseRemote(id, entry));
            }
            // 只解析 frontmatter；文件包在 detail/files 时按 dataId 单条读取（见 filesOf）。
            // 仅裁剪已删除技能，避免 Nacos 抖动时把文件缓存整份丢掉。
            fileCache.retainAll(byId.keySet());
            lastSuccessfulListAt = System.currentTimeMillis();
            log.info("[Skill] 从 Nacos 加载 {} 个技能配置", byId.size());
            return new Snapshot(
                    List.copyOf(byId.values()), Map.copyOf(byId), System.currentTimeMillis());
        } finally {
            SkillMetrics.catalogBuilt(SOURCE_NACOS, System.nanoTime() - started);
        }
    }

    /**
     * 按需加载并校验文件包：单条读取 {@code <id>.files.json}，哈希与正文一致才生效。
     * 结果按 TTL 缓存，避免管理台每次翻页都重新拉取大体积文件正文。
     */
    private List<SkillFilePackage.SkillFile> filesOf(RemoteSkill remote) {
        if (!fileSettings.enabled()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        String mdHash = SkillFilePackage.mdHash(remote.content());
        CachedFiles cached = fileCache.get(remote.id());
        if (cached != null
                && cached.mdHash().equals(mdHash)
                && now - cached.fetchedAt() < ttlMillis) {
            return cached.files();
        }
        if (cached != null) {
            fileCache.remove(remote.id());
        }
        List<SkillFilePackage.SkillFile> files = List.of();
        Optional<String> raw = client.fetch(SkillFilePackage.dataId(remote.id()));
        if (raw.isPresent()) {
            Optional<SkillFilePackage.Parsed> parsed = SkillFilePackage.parse(raw.get());
            if (parsed.isEmpty()) {
                log.warn("[Skill] 技能 {} 的文件包 JSON 无效或版本不支持，已忽略", remote.id());
            } else if (!parsed.get().mdHash().equals(mdHash)) {
                log.warn(
                        "[Skill] 技能 {} 的文件包与正文哈希不一致，已忽略文件（请保存正文后重新发布文件）",
                        remote.id());
            } else {
                try {
                    files = SkillFilePackage.normalizeAll(parsed.get().files(), fileSettings);
                } catch (RuntimeException e) {
                    log.warn(
                            "[Skill] 技能 {} 的文件包校验失败，已忽略文件：{}",
                            remote.id(),
                            e.getMessage());
                }
            }
        }
        fileCache.put(remote.id(), new CachedFiles(now, mdHash, fileBytes(files), files));
        return files;
    }

    private static long fileBytes(List<SkillFilePackage.SkillFile> files) {
        long total = 0L;
        for (SkillFilePackage.SkillFile file : files) {
            total += file.content().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
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
                executionEnabled,
                metadata);
    }

    /** 列表阶段不加载文件包，fileCount 固定为 0，具体文件在详情/文件接口按需读取。 */
    private static SkillSummary summary(RemoteSkill remote, boolean builtin) {
        return new SkillSummary(
                remote.id(),
                remote.description(),
                SOURCE_NACOS,
                builtin,
                builtin,
                remote.updatedAt(),
                remote.content().length(),
                0,
                "");
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
                0,
                "");
    }

    private static List<SkillFilePackage.Summary> summaries(
            List<SkillFilePackage.SkillFile> files) {
        return files.stream().map(SkillFilePackage::summary).toList();
    }

    private static String normalizeId(String id) {
        return SkillIds.normalize(id);
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法管理技能");
        }
    }
}
