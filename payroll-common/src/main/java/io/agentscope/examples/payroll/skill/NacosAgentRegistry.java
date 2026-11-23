package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.repository.AgentDefinitionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 专家智能体注册表：默认把整个 Agent 列表作为一个 JSON 配置存储在 Nacos
 * （dataId=payroll-agents.json），读多写少，读路径带 TTL 缓存。
 *
 * <p>{@code payroll.definitions.source=db} 时定义落库（M5.1），Nacos 退化为通知通道：
 * 写路径先提交数据库，再 best-effort 发布快照，失败只告警不回滚。
 *
 * <p>Nacos 未启用或配置缺失时，列表回退到由三个内置角色技能生成的默认智能体；
 * 首次写入会把当前完整列表（含内置默认）持久化到 Nacos。
 */
public final class NacosAgentRegistry {

    public static final String DATA_ID = "payroll-agents.json";
    public static final String DEFAULT_ACTIVE_AGENT = RoleSkills.CALCULATOR;
    public static final String SOURCE_NACOS = "nacos";
    public static final String SOURCE_DB = "db";

    private static final Logger log = LoggerFactory.getLogger(NacosAgentRegistry.class);

    /** 配置快照：items 为解析结果，revision 为原始文档内容指纹。 */
    public record Snapshot<T>(List<T> items, String revision) {
    }

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final NacosRuntimeSettings runtime;
    private final AgentDefinitionRepository definitionRepository;
    private final boolean dbSource;
    // 兼容前置：新增字段（如 model）时旧版本必须能读取新配置，未知字段直接忽略。
    private final ObjectMapper json =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
    private final long ttlMillis;

    private volatile Snapshot<AgentDefinition> cache;
    private volatile long fetchedAt;

    public NacosAgentRegistry() {
        this(NacosSkillSettings.fromEnvironment());
    }

    NacosAgentRegistry(NacosSkillSettings settings) {
        this(settings, new NacosRuntimeSettings(settings));
    }

    /** 与流程注册表共享同一份运行时设置缓存，避免两个实例各自读到旧模式。 */
    public NacosAgentRegistry(NacosSkillSettings settings, NacosRuntimeSettings runtime) {
        this(settings, runtime, null, SOURCE_NACOS);
    }

    /** M5.1：source=db 时定义读写走数据库，Nacos 只保留运行时设置与变更通知。 */
    public NacosAgentRegistry(
            NacosSkillSettings settings,
            NacosRuntimeSettings runtime,
            AgentDefinitionRepository definitionRepository,
            String source) {
        this.settings = settings;
        this.runtime = runtime;
        this.definitionRepository = definitionRepository;
        this.dbSource = SOURCE_DB.equalsIgnoreCase(source == null ? "" : source.trim());
        if (dbSource && definitionRepository == null) {
            throw new IllegalArgumentException(
                    "payroll.definitions.source=db 时必须提供 AgentDefinitionRepository");
        }
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
        log.info(
                "[Agent] 定义来源={}，Nacos {}",
                dbSource ? SOURCE_DB : SOURCE_NACOS,
                settings.enabled() ? settings.location() : "未启用");
    }

    public boolean writable() {
        return dbSource || settings.enabled();
    }

    public String location() {
        if (dbSource) {
            return "数据库 agent_definition 表";
        }
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    public List<AgentDefinition> list() {
        return snapshot().items();
    }

    /** 当前 agents 文档内容指纹（读路径，TTL 内缓存）。 */
    public String revision() {
        return snapshot().revision();
    }

    /** 运行时设置文档指纹；未启用时为空指纹。 */
    public String settingsRevision() {
        return settings.enabled() ? runtime.revision() : ConfigRevision.ABSENT;
    }

    private Snapshot<AgentDefinition> snapshot() {
        long now = System.currentTimeMillis();
        Snapshot<AgentDefinition> snapshot = cache;
        if (snapshot != null && now - fetchedAt < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cache;
            if (snapshot != null && now - fetchedAt < ttlMillis) {
                return snapshot;
            }
            snapshot = load(snapshot);
            cache = snapshot;
            fetchedAt = now;
            return snapshot;
        }
    }

    /** 管理台展示用的核算智能体；未配置或不可用时回退默认计算专家，不参与对话路由。 */
    public AgentDefinition activeAgent() {
        String configured = configuredAgentId();
        List<AgentDefinition> agents = list();
        Optional<AgentDefinition> matched =
                agents.stream()
                        .filter(a -> a.id().equals(configured))
                        .filter(a -> AgentDefinition.STATUS_ONLINE.equals(a.status()))
                        .findFirst();
        if (matched.isPresent()) {
            return matched.get();
        }
        if (!configured.isBlank()) {
            log.warn("[Agent] 配置的核算智能体 {} 不可用（未找到或已下架）", configured);
        }
        return agents.stream()
                .filter(a -> a.id().equals(DEFAULT_ACTIVE_AGENT))
                .findFirst()
                .orElseGet(() -> builtinAgents().get(0));
    }

    /**
     * 对话实际生效的智能体：未配置返回 null（走内置三段流水线）；
     * 配置了但不存在或已下架时抛出异常，禁止静默回退到薪酬计算智能体。
     */
    public AgentDefinition effectiveAgent() {
        String configured = configuredAgentId();
        if (configured.isBlank()) {
            return null;
        }
        AgentDefinition agent = list().stream()
                .filter(a -> a.id().equals(configured))
                .findFirst()
                .orElse(null);
        if (agent == null) {
            throw new PayrollException(
                    "已配置的核算智能体「" + configured
                            + "」不存在，已阻止回退薪酬流水线；请在 Agent 管理重新选择或取消默认智能体");
        }
        if (!AgentDefinition.STATUS_ONLINE.equals(agent.status())) {
            throw new PayrollException(
                    "已配置的核算智能体「" + agent.name()
                            + "」已下架，已阻止回退薪酬流水线；请重新上架、改选或取消默认智能体");
        }
        return agent;
    }

    /** 管理员显式配置的智能体 id；仅 AGENT 模式非空。 */
    public String configuredAgentId() {
        return settings.enabled() ? runtime.activeAgentId() : "";
    }

    /** 管理员配置的核算智能体 id；未配置时返回默认计算专家。 */
    public String activeAgentId() {
        String configured = configuredAgentId();
        return configured.isBlank() ? DEFAULT_ACTIVE_AGENT : configured;
    }

    /** 设置全局核算智能体，只允许公开且已上架的智能体。 */
    public synchronized String setActiveAgent(String id) {
        return setActiveAgent(id, null);
    }

    /** @param expectedRevision 运行时设置文档指纹；null/空白跳过校验（滚动发布兼容） */
    public synchronized String setActiveAgent(String id, String expectedRevision) {
        requireWritable();
        AgentDefinition agent =
                list().stream()
                        .filter(a -> a.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new PayrollException("智能体不存在：" + id));
        if (!AgentDefinition.STATUS_ONLINE.equals(agent.status())) {
            throw new PayrollException("只有已上架的智能体才能用于薪酬核算");
        }
        if (!AgentDefinition.VISIBILITY_PUBLIC.equals(agent.visibility())) {
            throw new PayrollException("只有公开的智能体才能用于薪酬核算");
        }
        runtime.activateAgent(id, expectedRevision);
        log.info("[Agent] 核算智能体已设置为 {}（{}）", agent.name(), id);
        return id;
    }

    /** 取消全局核算智能体：对话回到内置三段流水线，与全局默认流程互斥。 */
    public synchronized void clearActiveAgent() {
        clearActiveAgent(null);
    }

    public synchronized void clearActiveAgent(String expectedRevision) {
        requireWritable();
        runtime.clearAgent(expectedRevision);
        log.info("[Agent] 已取消全局核算智能体，对话回到内置流水线");
    }

    /** 写操作确认开关的 Nacos 覆盖值；未启用或未配置该键时为空，由调用方回退环境变量。 */
    public Optional<Boolean> actionConfirmOverride() {
        return settings.enabled() ? runtime.actionConfirmOverride() : Optional.empty();
    }

    /** 管理台切换写操作确认；与模式/默认流程共用同一份运行时设置文档。 */
    public synchronized void setActionConfirm(boolean enabled) {
        setActionConfirm(enabled, null);
    }

    public synchronized void setActionConfirm(boolean enabled, String expectedRevision) {
        requireWritable();
        runtime.setActionConfirm(enabled, expectedRevision);
        log.info("[Agent] 写操作确认已{}（Nacos {}）", enabled ? "开启" : "关闭",
                NacosRuntimeSettings.DATA_ID);
    }

    /** 新建或更新（按 id 匹配）。返回保存后的定义。 */
    public synchronized AgentDefinition save(AgentDefinition input, String operator) {
        return save(input, operator, null, true);
    }

    /**
     * @param keepModelWhenEmpty 旧表单不带模型字段时保留已有绑定；管理台显式「继承默认」时传 false
     */
    public synchronized AgentDefinition save(
            AgentDefinition input, String operator, boolean keepModelWhenEmpty) {
        return save(input, operator, null, keepModelWhenEmpty);
    }

    /** @param expectedRevision agents 文档指纹；null/空白跳过校验（滚动发布兼容） */
    public synchronized AgentDefinition save(
            AgentDefinition input,
            String operator,
            String expectedRevision,
            boolean keepModelWhenEmpty) {
        requireDefinitionsWritable();
        Snapshot<AgentDefinition> current = reloadForWrite();
        requireRevision(current.revision(), expectedRevision, DATA_ID);
        List<AgentDefinition> agents = new ArrayList<>(current.items());
        LocalDateTime now = LocalDateTime.now();
        int index = -1;
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).id().equals(input.id())) {
                index = i;
                break;
            }
        }
        AgentDefinition saved;
        if (index >= 0) {
            AgentDefinition existing = agents.get(index);
            saved = new AgentDefinition(
                    existing.id(),
                    input.name(),
                    input.avatar(),
                    input.description(),
                    input.tags(),
                    input.skillIds(),
                    input.visibility(),
                    input.status(),
                    existing.createdBy(),
                    existing.createdAt(),
                    now,
                    // 规格 6.2：未提交能力声明时保留已有声明，避免旧表单保存后静默丢能力。
                    input.capabilities().isEmpty()
                            ? existing.capabilities()
                            : input.capabilities(),
                    keepModelWhenEmpty && input.model().isEmpty()
                            ? existing.model()
                            : input.model());
            agents.set(index, saved);
        } else {
            String id = input.id() == null || input.id().isBlank()
                    ? "agent-" + UUID.randomUUID().toString().substring(0, 8)
                    : input.id();
            saved = new AgentDefinition(
                    id,
                    input.name(),
                    input.avatar(),
                    input.description(),
                    input.tags(),
                    input.skillIds(),
                    input.visibility(),
                    input.status(),
                    operator,
                    now,
                    now,
                    input.capabilities(),
                    input.model());
            agents.add(saved);
        }
        if (dbSource) {
            definitionRepository.save(saved, operator, current.revision());
            invalidateCache();
            notifyNacos(agents);
        } else {
            persist(agents);
        }
        return saved;
    }

    public synchronized boolean delete(String id) {
        return delete(id, null);
    }

    public synchronized boolean delete(String id, String expectedRevision) {
        requireDefinitionsWritable();
        Snapshot<AgentDefinition> current = reloadForWrite();
        requireRevision(current.revision(), expectedRevision, DATA_ID);
        List<AgentDefinition> agents = new ArrayList<>(current.items());
        boolean removed = agents.removeIf(agent -> agent.id().equals(id));
        if (removed) {
            if (dbSource) {
                if (definitionRepository.delete(id, current.revision())) {
                    invalidateCache();
                    notifyNacos(agents);
                }
            } else {
                persist(agents);
            }
        }
        return removed;
    }

    /** 定义写权限：DB 模式不依赖 Nacos；运行时设置仍单独要求 Nacos 可用。 */
    private void requireDefinitionsWritable() {
        if (!writable()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法保存智能体定义");
        }
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法保存运行时设置");
        }
    }

    /** agents 文档的并发写入校验；运行时设置文档由 {@link NacosRuntimeSettings} 校验。 */
    private void requireRevision(String current, String expected, String dataId) {
        if (expected == null || expected.isBlank()) {
            log.warn("[Agent] 写入 {} 未携带 revision，跳过并发校验（revision_missing=true）", dataId);
            return;
        }
        if (!current.equals(expected)) {
            throw new PayrollConflictException("配置已被他人修改，请刷新后重试", current);
        }
    }

    /**
     * 读取失败时保留上次成功目录：临时网络抖动不能让自定义智能体凭空消失，
     * 否则引用它的默认流程会校验失败并静默回退到薪酬链路。
     *
     * <p>DB 模式不做任何 Nacos 回退：读失败直接 503，避免双源打架。
     */
    private Snapshot<AgentDefinition> load(Snapshot<AgentDefinition> previous) {
        if (dbSource) {
            AgentDefinitionRepository.Snapshot loaded = definitionRepository.load();
            return new Snapshot<>(loaded.items(), loaded.revision());
        }
        if (!settings.enabled()) {
            return new Snapshot<>(builtinAgents(), ConfigRevision.ABSENT);
        }
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case ERROR -> {
                if (previous != null && !previous.items().isEmpty()) {
                    log.warn(
                            "[Agent] Nacos 读取 {} 失败，沿用上次成功加载的 {} 个智能体定义：{}",
                            DATA_ID,
                            previous.items().size(),
                            fetched.message());
                    return previous;
                }
                log.warn("[Agent] Nacos 读取 {} 失败，回退内置默认智能体：{}", DATA_ID, fetched.message());
                return new Snapshot<>(builtinAgents(), ConfigRevision.ABSENT);
            }
            case ABSENT -> {
                if (previous != null && !previous.items().isEmpty()) {
                    log.warn(
                            "[Agent] Nacos 未提供 {}，沿用上次成功加载的 {} 个智能体定义",
                            DATA_ID,
                            previous.items().size());
                    return new Snapshot<>(previous.items(), ConfigRevision.ABSENT);
                }
                log.info("[Agent] Nacos 未提供 {}，使用内置默认智能体", DATA_ID);
                return new Snapshot<>(builtinAgents(), ConfigRevision.ABSENT);
            }
            default -> {
                String raw = fetched.entry().content();
                try {
                    return new Snapshot<>(parseAgents(raw), ConfigRevision.revisionOf(raw));
                } catch (Exception e) {
                    if (previous != null && !previous.items().isEmpty()) {
                        log.warn(
                                "[Agent] 解析 Nacos 配置 {} 失败，沿用上次成功加载的 {} 个智能体定义：{}",
                                DATA_ID,
                                previous.items().size(),
                                e.getMessage());
                        return previous;
                    }
                    log.warn("[Agent] 解析 Nacos 配置 {} 失败，回退内置默认智能体：{}", DATA_ID, e.getMessage());
                    return new Snapshot<>(builtinAgents(), ConfigRevision.ABSENT);
                }
            }
        }
    }

    /** 写前重读：解析失败或读取失败一律 503，禁止用旧数据写回。 */
    private Snapshot<AgentDefinition> reloadForWrite() {
        if (dbSource) {
            AgentDefinitionRepository.Snapshot loaded = definitionRepository.load();
            return new Snapshot<>(loaded.items(), loaded.revision());
        }
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case OK -> {
                String raw = fetched.entry().content();
                try {
                    return new Snapshot<>(parseAgents(raw), ConfigRevision.revisionOf(raw));
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ABSENT -> {
                return new Snapshot<>(builtinAgents(), ConfigRevision.ABSENT);
            }
            default -> throw new PayrollUnavailableException(
                    "读取 " + DATA_ID + " 失败：" + fetched.message());
        }
    }

    /** 解析智能体数组；根节点不是数组或没有可用条目时抛异常。 */
    private List<AgentDefinition> parseAgents(String raw) throws Exception {
        JsonNode root = json.readTree(raw);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("配置根节点不是 JSON 数组");
        }
        List<AgentDefinition> agents = new ArrayList<>(root.size());
        List<String> skipped = new ArrayList<>();
        for (JsonNode node : root) {
            try {
                AgentDefinition agent = json.treeToValue(node, AgentDefinition.class);
                if (agent == null || agent.id() == null || agent.id().isBlank()
                        || agent.name() == null || agent.name().isBlank()) {
                    skipped.add(node.path("id").asText("<无 id>"));
                    continue;
                }
                agents.add(agent);
            } catch (Exception perEntry) {
                // 单条结构异常只跳过该条，不能让整份配置失效。
                skipped.add(node.path("id").asText("<无 id>"));
                log.warn(
                        "[Agent] 跳过无法解析的智能体配置（id={}）：{}",
                        node.path("id").asText("<无 id>"),
                        perEntry.getMessage());
            }
        }
        if (agents.isEmpty()) {
            throw new IllegalStateException("没有可用的智能体配置（跳过 " + skipped.size() + " 条）");
        }
        if (!skipped.isEmpty()) {
            log.warn("[Agent] 配置 {} 有 {} 条无效条目已跳过：{}", DATA_ID, skipped.size(), skipped);
        }
        log.info("[Agent] 从 Nacos 加载 {} 个智能体定义", agents.size());
        return List.copyOf(agents);
    }

    private void persist(List<AgentDefinition> agents) {
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(agents);
            if (!client.publish(DATA_ID, content)) {
                throw new io.agentscope.examples.payroll.domain.PayrollException(
                        "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
            cache = new Snapshot<>(List.copyOf(agents), ConfigRevision.revisionOf(content));
            fetchedAt = System.currentTimeMillis();
            log.info("[Agent] 已保存智能体配置（共 {} 个）", agents.size());
        } catch (io.agentscope.examples.payroll.domain.PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new io.agentscope.examples.payroll.domain.PayrollException(
                    "序列化智能体配置失败：" + e.getMessage());
        }
    }

    /**
     * DB 写入后的 best-effort 通知：数据库是权威，Nacos 发布失败只告警不回滚。
     */
    private void notifyNacos(List<AgentDefinition> agents) {
        if (!settings.enabled()) {
            return;
        }
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(agents);
            if (!client.publish(DATA_ID, content)) {
                log.warn("[Agent] 数据库已保存，但同步 Nacos {} 失败（DB 为权威，不回滚）", DATA_ID);
            }
        } catch (Exception e) {
            log.warn(
                    "[Agent] 数据库已保存，但同步 Nacos {} 异常：{}（DB 为权威，不回滚）",
                    DATA_ID,
                    e.getMessage());
        }
    }

    private void invalidateCache() {
        cache = null;
        fetchedAt = 0L;
    }

    /**
     * M5.1 导入器：source=db 且表为空时，从 Nacos 读取一次现有目录。
     *
     * @return 导入条数；非 DB 模式、Nacos 未启用/不可用或表非空时返回 0
     */
    public synchronized int importFromNacosIfEmpty() {
        if (!dbSource) {
            return 0;
        }
        if (!settings.enabled()) {
            log.info("[Agent] Nacos 未启用，跳过智能体定义导入");
            return 0;
        }
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        if (fetched.status() != NacosConfigClient.FetchResult.Status.OK) {
            log.warn("[Agent] 读取 Nacos {} 失败，跳过导入：{}", DATA_ID, fetched.message());
            return 0;
        }
        try {
            List<AgentDefinition> agents = parseAgents(fetched.entry().content());
            int imported = definitionRepository.importIfEmpty(agents, "nacos-import");
            if (imported > 0) {
                log.info("[Agent] 已从 Nacos 导入 {} 个智能体定义到数据库", imported);
            } else {
                log.info("[Agent] agent_definition 表非空，跳过 Nacos 导入（幂等）");
            }
            invalidateCache();
            return imported;
        } catch (Exception e) {
            log.warn("[Agent] 从 Nacos 导入智能体定义失败：{}", e.getMessage());
            return 0;
        }
    }

    private static List<AgentDefinition> builtinAgents() {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                new AgentDefinition(
                        RoleSkills.CALCULATOR,
                        "薪酬计算专家",
                        "🧮",
                        RoleSkills.description(RoleSkills.CALCULATOR),
                        List.of("薪酬", "内置"),
                        List.of(RoleSkills.CALCULATOR),
                        AgentDefinition.VISIBILITY_PUBLIC,
                        AgentDefinition.STATUS_ONLINE,
                        "system",
                        now,
                        now,
                        List.of(AgentDefinition.CAPABILITY_CALC)),
                new AgentDefinition(
                        RoleSkills.VALIDATOR,
                        "薪酬复核专家",
                        "🔍",
                        RoleSkills.description(RoleSkills.VALIDATOR),
                        List.of("薪酬", "内置"),
                        List.of(RoleSkills.VALIDATOR),
                        AgentDefinition.VISIBILITY_PUBLIC,
                        AgentDefinition.STATUS_ONLINE,
                        "system",
                        now,
                        now,
                        List.of(AgentDefinition.CAPABILITY_CALC)),
                new AgentDefinition(
                        RoleSkills.ACCOUNTANT,
                        "薪酬入账专家",
                        "📒",
                        RoleSkills.description(RoleSkills.ACCOUNTANT),
                        List.of("薪酬", "内置"),
                        List.of(RoleSkills.ACCOUNTANT),
                        AgentDefinition.VISIBILITY_PUBLIC,
                        AgentDefinition.STATUS_ONLINE,
                        "system",
                        now,
                        now,
                        List.of(AgentDefinition.CAPABILITY_CALC)));
    }
}
