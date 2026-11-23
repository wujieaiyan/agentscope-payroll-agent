package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * db 源的运行期读路径（规格 §7）：目录单查询，正文与资源整批装配。
 *
 * <p>与 {@link NacosSkillRepository} 有三处刻意不同：
 * <ul>
 *   <li>目录行自带 {@code resource_hash}，第一次读目录就能给出真指纹，不需要 Nacos 那套
 *       「正文进缓存后才补资源指纹」的补偿（§18 T-03）。
 *   <li>资源随正文一次装完，不做惰性逐文件读取：{@link AgentSkill} 一旦构建就不可变，
 *       装完才发现少文件只能重建智能体（§7.2）。
 *   <li>库连不上且没有历史快照时抛 {@link PayrollUnavailableException}，由
 *       {@link CompositeSkillRepository} 计降级并让内置源顶上；不静默返回空目录。
 * </ul>
 *
 * <p>缓存两层：目录快照按 TTL（{@code PAYROLL_SKILL_CATALOG_TTL_MS}）整体替换；正文按内容身份
 * （{@code version_id} + {@code resource_hash}）命中，身份不符即回源，因此条目本身不设 TTL——
 * 版本行不可变、字节按指纹寻址，身份相同就意味着内容相同。配额沿用 Nacos 源的
 * 256 条 / 8,000,000 字符，切换存储介质不改变内存画像。
 */
public final class DbSkillRepository implements AgentSkillRepository, PayrollSkillCatalog {

    public static final String SOURCE = JdbcSkillStore.SOURCE_DB;

    /** 目录快照有效期，键名与规格 §16 一致。 */
    public static final String TTL_ENV = "PAYROLL_SKILL_CATALOG_TTL_MS";

    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    /** 正文缓存上限：256 条 / 约 8M 字符，与 {@code NacosSkillRepository} 同值。 */
    static final int MAX_CACHED_ENTRIES = 256;

    static final long MAX_CACHED_CHARS = 8_000_000L;

    private static final Logger log = LoggerFactory.getLogger(DbSkillRepository.class);

    /**
     * 已装配的技能。{@code versionId}/{@code resourceHash} 是命中条件而不是记录字段：
     * 目录行说正文换了，缓存里那份就作废。
     */
    private record Entry(long versionId, String resourceHash, AgentSkill skill, long chars) {}

    private record Catalog(Map<String, Row> rows, long fetchedAt) {}

    private final SkillRuntimeReader reader;
    private final LongSupplier clock;
    private final long ttlMillis;
    private final Map<String, Entry> cache = new LinkedHashMap<>(64, 0.75F, true);
    private final Set<String> missingWarned = ConcurrentHashMap.newKeySet();

    private long cachedChars;
    private volatile Catalog catalog;
    private volatile Catalog lastGood;

    public DbSkillRepository(SkillRuntimeReader reader) {
        this(reader, ttlFromEnvironment(), System::currentTimeMillis);
    }

    /** 容器装配用：TTL 来自 Spring 键 {@code payroll.skill.catalog.ttl-ms}，不再读环境变量。 */
    public DbSkillRepository(SkillRuntimeReader reader, long ttlMillis) {
        this(reader, ttlMillis, System::currentTimeMillis);
    }

    DbSkillRepository(SkillRuntimeReader reader, long ttlMillis, LongSupplier clock) {
        if (reader == null) {
            throw new IllegalArgumentException("技能读端口必填");
        }
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("技能目录缓存时间不能为负");
        }
        this.reader = reader;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    private static long ttlFromEnvironment() {
        String value = System.getenv(TTL_ENV);
        if (value == null || value.isBlank()) {
            return DEFAULT_TTL_MILLIS;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(TTL_ENV + " 必须是整数，当前值：" + value);
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        Row row = rowOf(name);
        return row == null ? null : assemble(List.of(row)).get(row.id());
    }

    /** 目录行在即视为存在：装配一次正文只为了回答「有没有」，代价与收益不匹配。 */
    @Override
    public boolean skillExists(String name) {
        return rowOf(name) != null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(catalog().rows().keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        Map<String, AgentSkill> skills = assemble(catalog().rows().values());
        List<AgentSkill> ordered = new ArrayList<>(catalog().rows().size());
        for (String id : catalog().rows().keySet()) {
            AgentSkill skill = skills.get(id);
            if (skill != null) {
                ordered.add(skill);
            }
        }
        return ordered;
    }

    @Override
    public List<SkillManifest> listManifests() {
        List<SkillManifest> manifests = new ArrayList<>();
        for (Row row : catalog().rows().values()) {
            manifests.add(manifestOf(row));
        }
        return List.copyOf(manifests);
    }

    @Override
    public List<AgentSkill> getSkills(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        Set<String> order = new LinkedHashSet<>();
        for (String id : ids) {
            Row row = rowOf(id);
            if (row == null) {
                continue;
            }
            rows.putIfAbsent(row.id(), row);
            order.add(row.id());
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, AgentSkill> skills = assemble(rows.values());
        List<AgentSkill> result = new ArrayList<>(order.size());
        for (String id : order) {
            AgentSkill skill = skills.get(id);
            if (skill != null) {
                result.add(skill);
            }
        }
        return result;
    }

    @Override
    public Optional<SkillManifest> manifest(String id) {
        Row row = rowOf(id);
        return row == null ? Optional.empty() : Optional.of(manifestOf(row));
    }

    @Override
    public Map<String, String> nameById() {
        Map<String, String> names = new LinkedHashMap<>();
        catalog().rows().keySet().forEach(id -> names.put(id, id));
        return Collections.unmodifiableMap(names);
    }

    /** 丢弃目录与正文快照：管理台刚写入的技能不必等 TTL 就能被下一次构建读到。 */
    @Override
    public void invalidate() {
        catalog = null;
        synchronized (cache) {
            cache.clear();
            cachedChars = 0;
        }
        missingWarned.clear();
    }

    /**
     * 只丢弃目录快照，正文缓存留着——{@link SkillInvalidationPoller} 每发现一次聚合 revision
     * 变化就调一次，而 revision 是全表口径：任何一个技能的保存都会让它变。跟着清正文缓存等于
     * 每改一个技能就把全部技能重新装配一遍，§7.1 的内容身份缓存白做。
     */
    public void refresh() {
        catalog = null;
    }

    /**
     * 目录快照。构建耗时由 {@link JdbcSkillStore#catalog()} 打点，这里不再计一次，
     * 否则同一次查询会在 {@code skill.catalog.build.duration} 上留下两条记录。
     */
    private Catalog catalog() {
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
            return rebuild(now);
        }
    }

    /**
     * 重建目录。失败时沿用上次成功快照，并把失败时刻计入 TTL——库整段不可用时，
     * 每次读都重试一次全表查询只会把连接池拖死。
     *
     * <p>没有历史快照（冷启动即断库）则原样抛出：那正是 §7.1 要求「亮黄 + 计数」的场景，
     * 吞掉异常就等于让 {@code CompositeSkillRepository} 以为库里真的没有技能。
     */
    private Catalog rebuild(long now) {
        try {
            Map<String, Row> rows = new LinkedHashMap<>();
            for (Row row : reader.catalog()) {
                if (row != null) {
                    rows.putIfAbsent(row.id(), row);
                }
            }
            Catalog next = new Catalog(Collections.unmodifiableMap(rows), now);
            catalog = next;
            lastGood = next;
            return next;
        } catch (PayrollUnavailableException e) {
            Catalog previous = lastGood;
            if (previous == null) {
                throw e;
            }
            log.warn(
                    "[Skill] 读取技能目录失败，沿用上次成功快照（{} 个技能）：{}",
                    previous.rows().size(),
                    e.getMessage());
            Catalog retry = new Catalog(previous.rows(), now);
            catalog = retry;
            return retry;
        }
    }

    /** 快照里没有的 id 单行补查：另一节点刚创建的技能不该让调用方等一个 TTL。 */
    private Row rowOf(String id) {
        if (!SkillIds.isValid(id == null ? null : id.trim())) {
            return null;
        }
        String skillId = id.trim();
        Row row = catalog().rows().get(skillId);
        return row != null ? row : reader.find(skillId).orElse(null);
    }

    /** 批量装配：缓存命中的直接复用，其余一次 {@link SkillRuntimeReader#runtimeData} 取回。 */
    private Map<String, AgentSkill> assemble(Collection<Row> rows) {
        Map<String, AgentSkill> skills = new LinkedHashMap<>();
        List<Row> expired = new ArrayList<>();
        for (Row row : rows) {
            if (row == null) {
                continue;
            }
            Entry entry = cachedEntry(row);
            if (entry == null) {
                expired.add(row);
            } else if (entry.skill() != null) {
                skills.put(row.id(), entry.skill());
            }
        }
        if (!expired.isEmpty()) {
            // 回源失败不兜旧内容：指纹取自目录行，正文却是上一版的，
            // 智能体缓存会拿着新指纹装旧提示词——那是 V-09 明确禁止的静默降级。
            Map<String, Runtime> loaded = reader.runtimeData(expired);
            for (Row row : expired) {
                Runtime runtime = loaded.get(row.id());
                AgentSkill skill = runtime == null ? null : toSkill(row, runtime);
                long chars = skill == null ? 0L : skill.getSkillContent().length();
                put(row.id(), new Entry(row.versionId(), row.resourceHash(), skill, chars));
                SkillMetrics.contentLoaded(SOURCE, false, chars);
                if (skill == null) {
                    // 版本行缺失由读端口告警，这里只保证同一版本不重复刷日志。
                    missingWarned.add(row.id());
                } else {
                    if (missingWarned.remove(row.id())) {
                        log.info("[Skill] 技能 {} 已恢复到可读版本", row.id());
                    }
                    log.info(
                            "[Skill] 装配技能 {}（版本 {}，{} 字，{} 个文件，source=db）",
                            row.id(),
                            row.versionId(),
                            chars,
                            skill.getResourcePaths().size());
                    skills.put(row.id(), skill);
                }
            }
        }
        return skills;
    }

    private Entry cachedEntry(Row row) {
        Entry entry;
        synchronized (cache) {
            entry = cache.get(row.id());
        }
        if (entry == null
                || entry.versionId() != row.versionId()
                || !entry.resourceHash().equals(row.resourceHash())) {
            return null;
        }
        SkillMetrics.contentLoaded(SOURCE, true, entry.chars());
        return entry;
    }

    private AgentSkill toSkill(Row row, Runtime runtime) {
        AgentSkill.Builder builder =
                AgentSkill.builder()
                        .name(row.id())
                        .description(descriptionOf(row))
                        .skillContent(runtime.body())
                        .source(SOURCE);
        Map<String, Object> metadata = runtime.metadata();
        if (metadata != null && !metadata.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>(metadata);
            extra.remove("name");
            extra.remove("description");
            extra.forEach(builder::putMetadata);
        }
        runtime.resources().forEach(builder::addResource);
        return builder.build();
    }

    private static String descriptionOf(Row row) {
        return row.description().isBlank() ? RoleSkills.description(row.id()) : row.description();
    }

    /**
     * 目录条目：三个哈希/长度字段直接取列上的值。
     *
     * <p>必须与 {@code SkillManifests.fromSkill(装配结果)} 逐字段相等，否则同一技能在目录里
     * 和装配后是两个指纹，智能体缓存每次重建都会多跑一轮（§18 T-13 与 V-12 的前提）。
     */
    private static SkillManifest manifestOf(Row row) {
        return new SkillManifest(
                row.id(),
                row.id(),
                descriptionOf(row),
                row.tags(),
                row.version(),
                row.contentHash(),
                row.contentLength(),
                row.resourceHash(),
                row.executionEnabled(),
                SOURCE);
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

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(SOURCE, reader.location(), false);
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
