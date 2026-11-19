package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 Nacos 配置中心读取角色技能，dataId 约定为 {@code <skill-id>.md}，
 * 伴生 {@code <skill-id>.files.json} 解析为 {@link AgentSkill#getResources()}。
 *
 * <p>目录（id + manifest）按 TTL 缓存，一次 {@code list} 调用即拿到全部正文；
 * 正文按访问顺序 LRU 缓存，冷启动不逐条拉取。列表或正文读取失败时回退上次成功快照
 * 或代码内置版本。仓库只读。
 */
public final class NacosSkillRepository implements AgentSkillRepository, PayrollSkillCatalog {

    public static final String SOURCE = "nacos";

    /** 正文缓存上限：256 条 / 约 8M 字符（≈16MB），超出按最久未用淘汰。 */
    static final int MAX_CACHED_ENTRIES = 256;

    static final long MAX_CACHED_CHARS = 8_000_000L;

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);

    @FunctionalInterface
    interface ConfigFetcher {
        Optional<String> fetch(String dataId);
    }

    @FunctionalInterface
    interface ConfigLister {
        List<NacosConfigClient.ConfigEntry> list(String dataIdPattern);
    }

    private record Entry(long fetchedAt, AgentSkill skill, long chars) {
    }

    private record Catalog(Set<String> ids, Map<String, SkillManifest> manifests, long fetchedAt) {
    }

    private final NacosSkillSettings settings;
    private final SkillFileSettings fileSettings;
    private final ConfigFetcher fetcher;
    private final ConfigLister lister;
    private final LongSupplier clock;
    private final long ttlMillis;
    private final Map<String, Entry> cache = new LinkedHashMap<>(64, 0.75F, true);
    private final Set<String> missingWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private long cachedChars;
    private volatile Catalog catalog;
    private volatile Catalog lastGood;
    private volatile Catalog builtin;

    public NacosSkillRepository(NacosSkillSettings settings) {
        this(
                settings,
                nacosFetcher(settings),
                nacosLister(settings),
                System::currentTimeMillis,
                SkillFileSettings.fromEnvironment());
    }

    private static ConfigFetcher nacosFetcher(NacosSkillSettings settings) {
        NacosConfigClient client = new NacosConfigClient(settings);
        return client::fetch;
    }

    private static ConfigLister nacosLister(NacosSkillSettings settings) {
        NacosConfigClient client = new NacosConfigClient(settings);
        return client::list;
    }

    NacosSkillRepository(
            NacosSkillSettings settings, ConfigFetcher fetcher, LongSupplier clock) {
        this(settings, fetcher, null, clock, SkillFileSettings.defaults());
    }

    NacosSkillRepository(
            NacosSkillSettings settings,
            ConfigFetcher fetcher,
            ConfigLister lister,
            LongSupplier clock) {
        this(settings, fetcher, lister, clock, SkillFileSettings.defaults());
    }

    NacosSkillRepository(
            NacosSkillSettings settings,
            ConfigFetcher fetcher,
            ConfigLister lister,
            LongSupplier clock,
            SkillFileSettings fileSettings) {
        this.settings = settings;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
        this.fetcher = fetcher;
        this.lister = lister;
        this.clock = clock;
        this.ttlMillis = settings.ttl().toMillis();
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || !catalog().ids().contains(name)) {
            return null;
        }
        return cachedSkill(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> names = new ArrayList<>();
        for (String id : catalog().ids()) {
            if (cachedSkill(id) != null) {
                names.add(id);
            }
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> skills = new ArrayList<>();
        for (String id : catalog().ids()) {
            AgentSkill skill = cachedSkill(id);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    @Override
    public List<SkillManifest> listManifests() {
        List<SkillManifest> manifests = new ArrayList<>();
        for (SkillManifest manifest : catalog().manifests().values()) {
            manifests.add(enrich(manifest));
        }
        return List.copyOf(manifests);
    }

    @Override
    public List<AgentSkill> getSkills(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> known = catalog().ids();
        List<AgentSkill> skills = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (!known.contains(id)) {
                continue;
            }
            AgentSkill skill = cachedSkill(id);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    @Override
    public Optional<SkillManifest> manifest(String id) {
        if (id == null) {
            return Optional.empty();
        }
        SkillManifest base = catalog().manifests().get(id);
        return base == null ? Optional.empty() : Optional.of(enrich(base));
    }

    @Override
    public Map<String, String> nameById() {
        Map<String, String> names = new LinkedHashMap<>();
        catalog().manifests().forEach((id, manifest) -> names.put(id, manifest.name()));
        return Collections.unmodifiableMap(names);
    }

    /**
     * 目录快照：内置技能 + Nacos 中额外发布的 {@code <id>.md}。一次 list 调用同时得到
     * id 与 manifest，避免逐个技能再拉正文。列表失败时沿用上次成功快照：自定义技能
     * 没有内置兜底，瞬时失败若退回内置清单，模型只能报“角色技能未加载”。
     */
    private Catalog catalog() {
        if (lister == null) {
            return builtinCatalog();
        }
        long now = clock.getAsLong();
        Catalog snapshot = catalog;
        if (snapshot != null && now - snapshot.fetchedAt() < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = catalog;
            if (snapshot != null && now - snapshot.fetchedAt() < ttlMillis) {
                return snapshot;
            }
            long started = System.nanoTime();
            try {
                return rebuildCatalog(now);
            } finally {
                SkillMetrics.catalogBuilt(SOURCE, System.nanoTime() - started);
            }
        }
    }

    private Catalog rebuildCatalog(long now) {
        Set<String> ids = new LinkedHashSet<>(RoleSkills.ids());
        Map<String, SkillManifest> manifests = new LinkedHashMap<>();
        BuiltInSkillRepository.MANIFESTS.forEach((id, manifest) -> manifests.put(id, manifest));
        try {
            Set<String> remoteIds = new LinkedHashSet<>();
            Map<String, SkillManifest> remoteManifests = new LinkedHashMap<>();
            for (NacosConfigClient.ConfigEntry entry :
                    lister.list("*" + NacosSkillRegistry.DATA_ID_SUFFIX)) {
                String dataId = entry.dataId();
                if (dataId == null || !dataId.endsWith(NacosSkillRegistry.DATA_ID_SUFFIX)) {
                    continue;
                }
                String id =
                        dataId.substring(
                                0, dataId.length() - NacosSkillRegistry.DATA_ID_SUFFIX.length());
                if (NacosSkillRegistry.isValidId(id)) {
                    remoteIds.add(id);
                    remoteManifests.put(id, manifestOf(id, entry.content()));
                }
            }
            ids.addAll(remoteIds);
            manifests.putAll(remoteManifests);
        } catch (RuntimeException e) {
            Catalog previous = lastGood;
            if (previous != null) {
                log.warn(
                        "[Skill] 列出 Nacos 技能失败，沿用上次成功快照（{} 个技能）：{}",
                        previous.ids().size(),
                        e.getMessage());
                // 失败时刻也计入 TTL，避免 Nacos 故障期间每次调用都重试列表。
                Catalog retry = new Catalog(previous.ids(), previous.manifests(), now);
                catalog = retry;
                return retry;
            }
            log.warn("[Skill] 列出 Nacos 技能失败，仅保留内置技能：{}", e.getMessage());
        }
        Catalog next =
                new Catalog(
                        Collections.unmodifiableSet(new LinkedHashSet<>(ids)),
                        Collections.unmodifiableMap(manifests),
                        now);
        catalog = next;
        lastGood = next;
        return next;
    }

    /** 无列表能力（本地回退仓库）时只暴露内置技能。 */
    private Catalog builtinCatalog() {
        Catalog snapshot = builtin;
        if (snapshot != null) {
            return snapshot;
        }
        Map<String, SkillManifest> manifests = new LinkedHashMap<>(BuiltInSkillRepository.MANIFESTS);
        snapshot =
                new Catalog(
                        Collections.unmodifiableSet(new LinkedHashSet<>(manifests.keySet())),
                        Collections.unmodifiableMap(manifests),
                        Long.MAX_VALUE);
        builtin = snapshot;
        return snapshot;
    }

    /** 目录阶段只解析 frontmatter，不读伴生文件包；文件数在正文加载后按需补充。 */
    private SkillManifest manifestOf(String id, String markdown) {
        Map<String, Object> metadata = Map.of();
        String content = markdown == null ? "" : markdown;
        String description = "";
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            if (parsed != null) {
                if (parsed.hasFrontmatter()
                        && parsed.getContent() != null
                        && !parsed.getContent().isBlank()) {
                    content = parsed.getContent();
                }
                if (parsed.getMetadata() != null) {
                    metadata = parsed.getMetadata();
                    Object value = metadata.get("description");
                    if (value != null) {
                        description = value.toString().trim();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 解析 Nacos 技能 {} 元数据失败，按纯文本建立目录：{}", id, e.getMessage());
        }
        return SkillManifests.forId(id, description, content, metadata, SOURCE);
    }

    /** 正文已缓存时才补上 hasResources，避免目录阶段多读一次文件包。 */
    private SkillManifest enrich(SkillManifest manifest) {
        Entry entry;
        synchronized (cache) {
            entry = cache.get(manifest.id());
        }
        if (entry == null || entry.skill() == null) {
            return manifest;
        }
        return manifest.withHasResources(!entry.skill().getResources().isEmpty());
    }

    private AgentSkill cachedSkill(String id) {
        Entry entry = cachedEntry(id);
        return entry == null ? null : entry.skill();
    }

    private Entry cachedEntry(String id) {
        long now = clock.getAsLong();
        Entry entry;
        synchronized (cache) {
            entry = cache.get(id);
        }
        if (entry != null && now - entry.fetchedAt() < ttlMillis) {
            SkillMetrics.contentLoaded(SOURCE, true, entry.chars());
            return entry;
        }
        String dataId = id + ".md";
        Optional<String> body;
        try {
            body = fetcher.fetch(dataId);
        } catch (RuntimeException e) {
            if (entry != null && entry.skill() != null) {
                log.warn(
                        "[Skill] 读取 Nacos 配置 {} 异常，沿用上次成功加载的版本：{}",
                        dataId,
                        e.getMessage());
                Entry refreshed = new Entry(now, entry.skill(), entry.chars());
                put(id, refreshed);
                SkillMetrics.contentLoaded(SOURCE, false, refreshed.chars());
                return refreshed;
            }
            log.warn("[Skill] 读取 Nacos 配置 {} 异常：{}", dataId, e.getMessage());
            body = Optional.empty();
        }
        AgentSkill skill = body.map(markdown -> parse(id, markdown)).orElse(null);
        if (skill != null) {
            skill = withResources(skill);
        }
        long chars = skill == null ? 0 : skill.getSkillContent().length();
        Entry fresh = new Entry(now, skill, chars);
        put(id, fresh);
        SkillMetrics.contentLoaded(SOURCE, false, chars);
        if (skill == null) {
            if (missingWarned.add(id)) {
                log.warn("[Skill] Nacos 未提供 {}，技能 {} 回退代码内置版本", dataId, id);
            }
        } else {
            missingWarned.remove(id);
            if (entry == null || entry.skill() == null
                    || !entry.skill().getSkillContent().equals(skill.getSkillContent())) {
                log.info("[Skill] 从 Nacos 加载技能 {}（{} 字）", id, skill.getSkillContent().length());
            }
        }
        return fresh;
    }

    /** 访问顺序 LRU：先按条数再按字符数淘汰最久未用条目。 */
    private void put(String id, Entry entry) {
        synchronized (cache) {
            Entry previous = cache.remove(id);
            if (previous != null) {
                cachedChars -= previous.chars();
            }
            cache.put(id, entry);
            cachedChars += entry.chars();
            Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
            while ((cache.size() > MAX_CACHED_ENTRIES || cachedChars > MAX_CACHED_CHARS)
                    && it.hasNext()) {
                Map.Entry<String, Entry> eldest = it.next();
                cachedChars -= eldest.getValue().chars();
                it.remove();
            }
        }
    }

    private AgentSkill parse(String id, String markdown) {
        Map<String, Object> metadata = Map.of();
        String content = markdown;
        String description = "";
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            if (parsed != null) {
                if (parsed.hasFrontmatter()
                        && parsed.getContent() != null
                        && !parsed.getContent().isBlank()) {
                    content = parsed.getContent();
                }
                if (parsed.getMetadata() != null) {
                    metadata = parsed.getMetadata();
                    Object value = metadata.get("description");
                    if (value != null) {
                        description = value.toString().trim();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 解析 Nacos 技能 {} 失败，按纯文本使用：{}", id, e.getMessage());
        }
        if (description.isBlank()) {
            description = RoleSkills.description(id);
        }
        AgentSkill.Builder builder =
                AgentSkill.builder()
                        .name(id)
                        .description(description)
                        .skillContent(content)
                        .source(SOURCE);
        if (!metadata.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>(metadata);
            extra.remove("name");
            extra.remove("description");
            extra.forEach(builder::putMetadata);
        }
        return builder.build();
    }

    /**
     * 只有文件包哈希与正文一致时才挂载资源：正文改过但文件包没重发，说明两者版本错配，
     * 此时宁可不提供文件，也不能让模型读到旧参考。
     */
    private AgentSkill withResources(AgentSkill skill) {
        if (!fileSettings.enabled()) {
            return skill;
        }
        List<SkillFilePackage.SkillFile> files =
                loadFiles(skill.getName(), skill.getSkillContent());
        if (files.isEmpty()) {
            return skill;
        }
        AgentSkill.Builder builder = skill.toBuilder();
        files.forEach(file -> builder.addResource(file.path(), file.content()));
        return builder.build();
    }

    private List<SkillFilePackage.SkillFile> loadFiles(String id, String content) {
        String dataId = SkillFilePackage.dataId(id);
        Optional<String> raw;
        try {
            raw = fetcher.fetch(dataId);
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取技能 {} 的文件包异常，按无文件处理：{}", id, e.getMessage());
            return List.of();
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        Optional<SkillFilePackage.Parsed> parsed = SkillFilePackage.parse(raw.get());
        if (parsed.isEmpty()) {
            log.warn("[Skill] 技能 {} 的文件包 JSON 无效或版本不支持，已忽略", id);
            return List.of();
        }
        if (!parsed.get().mdHash().equals(SkillFilePackage.mdHash(content))) {
            log.warn("[Skill] 技能 {} 的文件包与正文哈希不一致，已忽略文件（请保存正文后重新发布文件）", id);
            return List.of();
        }
        try {
            return SkillFilePackage.normalizeAll(parsed.get().files(), fileSettings);
        } catch (RuntimeException e) {
            log.warn("[Skill] 技能 {} 的文件包校验失败，已忽略文件：{}", id, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(SOURCE, settings.location(), false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void close() {
    }
}
