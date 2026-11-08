package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 Nacos 配置中心读取角色技能，dataId 约定为 {@code <skill-id>.md}。
 *
 * <p>远程内容按 TTL 缓存，读取失败时对应技能返回 null，由
 * {@link CompositeSkillRepository} 回退到代码内置版本。仓库只读。
 */
public final class NacosSkillRepository implements AgentSkillRepository {

    public static final String SOURCE = "nacos";

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);

    @FunctionalInterface
    interface ConfigFetcher {
        Optional<String> fetch(String dataId);
    }

    @FunctionalInterface
    interface ConfigLister {
        List<NacosConfigClient.ConfigEntry> list(String dataIdPattern);
    }

    private record Entry(long fetchedAt, AgentSkill skill) {
    }

    private final NacosSkillSettings settings;
    private final ConfigFetcher fetcher;
    private final ConfigLister lister;
    private final LongSupplier clock;
    private final long ttlMillis;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> missingWarned = ConcurrentHashMap.newKeySet();

    private volatile List<String> knownIds;
    private volatile long idsFetchedAt;

    public NacosSkillRepository(NacosSkillSettings settings) {
        this(settings, nacosFetcher(settings), nacosLister(settings), System::currentTimeMillis);
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
        this(settings, fetcher, null, clock);
    }

    NacosSkillRepository(
            NacosSkillSettings settings,
            ConfigFetcher fetcher,
            ConfigLister lister,
            LongSupplier clock) {
        this.settings = settings;
        this.fetcher = fetcher;
        this.lister = lister;
        this.clock = clock;
        this.ttlMillis = settings.ttl().toMillis();
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || !knownIds().contains(name)) {
            return null;
        }
        return cachedSkill(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> names = new ArrayList<>();
        for (String id : knownIds()) {
            if (cachedSkill(id) != null) {
                names.add(id);
            }
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> skills = new ArrayList<>();
        for (String id : knownIds()) {
            AgentSkill skill = cachedSkill(id);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    /**
     * 内置技能 + Nacos 中额外发布的 {@code <id>.md}。列表按 TTL 缓存，
     * 使管理台新建的技能无需重启即可出现在可绑定技能清单里。
     */
    private List<String> knownIds() {
        if (lister == null) {
            return RoleSkills.ids();
        }
        long now = clock.getAsLong();
        List<String> snapshot = knownIds;
        if (snapshot != null && now - idsFetchedAt < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = knownIds;
            if (snapshot != null && now - idsFetchedAt < ttlMillis) {
                return snapshot;
            }
            Set<String> ids = new LinkedHashSet<>(RoleSkills.ids());
            try {
                for (NacosConfigClient.ConfigEntry entry : lister.list("*" + NacosSkillRegistry.DATA_ID_SUFFIX)) {
                    String dataId = entry.dataId();
                    if (dataId == null || !dataId.endsWith(NacosSkillRegistry.DATA_ID_SUFFIX)) {
                        continue;
                    }
                    String id =
                            dataId.substring(
                                    0, dataId.length() - NacosSkillRegistry.DATA_ID_SUFFIX.length());
                    if (NacosSkillRegistry.isValidId(id)) {
                        ids.add(id);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[Skill] 列出 Nacos 技能失败，仅保留内置技能：{}", e.getMessage());
            }
            snapshot = List.copyOf(ids);
            knownIds = snapshot;
            idsFetchedAt = now;
            return snapshot;
        }
    }

    private AgentSkill cachedSkill(String id) {
        long now = clock.getAsLong();
        Entry entry = cache.get(id);
        if (entry != null && now - entry.fetchedAt() < ttlMillis) {
            return entry.skill();
        }
        String dataId = id + ".md";
        Optional<String> body;
        try {
            body = fetcher.fetch(dataId);
        } catch (RuntimeException e) {
            log.warn("[Skill] 读取 Nacos 配置 {} 异常：{}", dataId, e.getMessage());
            body = Optional.empty();
        }
        AgentSkill skill = body.map(markdown -> parse(id, markdown)).orElse(null);
        cache.put(id, new Entry(now, skill));
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
        return skill;
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
