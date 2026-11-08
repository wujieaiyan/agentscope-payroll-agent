package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 专家智能体注册表：整个 Agent 列表作为一个 JSON 配置存储在 Nacos
 * （dataId=payroll-agents.json），读多写少，读路径带 TTL 缓存。
 *
 * <p>Nacos 未启用或配置缺失时，列表回退到由三个内置角色技能生成的默认智能体；
 * 首次写入会把当前完整列表（含内置默认）持久化到 Nacos。
 */
public final class NacosAgentRegistry {

    public static final String DATA_ID = "payroll-agents.json";
    public static final String SETTINGS_DATA_ID = "payroll-agent-settings.json";
    public static final String DEFAULT_ACTIVE_AGENT = RoleSkills.CALCULATOR;

    private static final Logger log = LoggerFactory.getLogger(NacosAgentRegistry.class);

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final long ttlMillis;

    private volatile List<AgentDefinition> cache;
    private volatile long fetchedAt;

    private volatile String cachedActiveAgent;
    private volatile long settingsFetchedAt;

    public NacosAgentRegistry() {
        this(NacosSkillSettings.fromEnvironment());
    }

    NacosAgentRegistry(NacosSkillSettings settings) {
        this.settings = settings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
    }

    public boolean writable() {
        return settings.enabled();
    }

    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    public List<AgentDefinition> list() {
        long now = System.currentTimeMillis();
        List<AgentDefinition> snapshot = cache;
        if (snapshot != null && now - fetchedAt < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cache;
            if (snapshot != null && now - fetchedAt < ttlMillis) {
                return snapshot;
            }
            snapshot = load();
            cache = snapshot;
            fetchedAt = now;
            return snapshot;
        }
    }

    /** 当前全局薪酬核算智能体；未配置、已下架或被删除时回退默认计算专家。 */
    public AgentDefinition activeAgent() {
        String configured = activeAgentId();
        List<AgentDefinition> agents = list();
        Optional<AgentDefinition> matched =
                agents.stream()
                        .filter(a -> a.id().equals(configured))
                        .filter(a -> AgentDefinition.STATUS_ONLINE.equals(a.status()))
                        .findFirst();
        if (matched.isPresent()) {
            return matched.get();
        }
        log.warn("[Agent] 核算智能体 {} 不可用（未找到或已下架），回退 {}", configured, DEFAULT_ACTIVE_AGENT);
        return agents.stream()
                .filter(a -> a.id().equals(DEFAULT_ACTIVE_AGENT))
                .findFirst()
                .orElseGet(() -> builtinAgents().get(0));
    }

    /** 管理员配置的核算智能体 id；Nacos 未启用或配置缺失时返回默认计算专家。 */
    public String activeAgentId() {
        if (!settings.enabled()) {
            return DEFAULT_ACTIVE_AGENT;
        }
        long now = System.currentTimeMillis();
        String snapshot = cachedActiveAgent;
        if (snapshot != null && now - settingsFetchedAt < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedActiveAgent;
            if (snapshot != null && now - settingsFetchedAt < ttlMillis) {
                return snapshot;
            }
            snapshot = loadActiveAgentId();
            cachedActiveAgent = snapshot;
            settingsFetchedAt = now;
            return snapshot;
        }
    }

    /** 设置全局核算智能体，只允许公开且已上架的智能体。 */
    public synchronized String setActiveAgent(String id) {
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
        Map<String, String> payload = Map.of("calculationAgentId", id);
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            if (!client.publish(SETTINGS_DATA_ID, content)) {
                throw new PayrollException(
                        "写入 Nacos 配置 " + SETTINGS_DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
        } catch (PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new PayrollException("序列化核算智能体配置失败：" + e.getMessage());
        }
        cachedActiveAgent = id;
        settingsFetchedAt = System.currentTimeMillis();
        log.info("[Agent] 核算智能体已设置为 {}（{}）", agent.name(), id);
        return id;
    }

    private String loadActiveAgentId() {
        Optional<String> body = client.fetch(SETTINGS_DATA_ID);
        if (body.isEmpty()) {
            return DEFAULT_ACTIVE_AGENT;
        }
        try {
            String id = json.readTree(body.get()).path("calculationAgentId").asText("").trim();
            return id.isBlank() ? DEFAULT_ACTIVE_AGENT : id;
        } catch (Exception e) {
            log.warn("[Agent] 解析 Nacos 配置 {} 失败，使用默认核算智能体：{}",
                    SETTINGS_DATA_ID, e.getMessage());
            return DEFAULT_ACTIVE_AGENT;
        }
    }

    /** 新建或更新（按 id 匹配）。返回保存后的定义。 */
    public synchronized AgentDefinition save(AgentDefinition input, String operator) {
        requireWritable();
        List<AgentDefinition> agents = new ArrayList<>(list());
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
                    now);
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
                    now);
            agents.add(saved);
        }
        persist(agents);
        return saved;
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        List<AgentDefinition> agents = new ArrayList<>(list());
        boolean removed = agents.removeIf(agent -> agent.id().equals(id));
        if (removed) {
            persist(agents);
        }
        return removed;
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法保存智能体定义");
        }
    }

    private List<AgentDefinition> load() {
        if (!settings.enabled()) {
            return builtinAgents();
        }
        Optional<String> body = client.fetch(DATA_ID);
        if (body.isEmpty()) {
            log.info("[Agent] Nacos 未提供 {}，使用内置默认智能体", DATA_ID);
            return builtinAgents();
        }
        try {
            List<AgentDefinition> agents =
                    json.readValue(
                            body.get(),
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, AgentDefinition.class));
            log.info("[Agent] 从 Nacos 加载 {} 个智能体定义", agents.size());
            return List.copyOf(agents);
        } catch (Exception e) {
            log.warn("[Agent] 解析 Nacos 配置 {} 失败，回退内置默认智能体：{}", DATA_ID, e.getMessage());
            return builtinAgents();
        }
    }

    private void persist(List<AgentDefinition> agents) {
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(agents);
            if (!client.publish(DATA_ID, content)) {
                throw new io.agentscope.examples.payroll.domain.PayrollException(
                        "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
            cache = List.copyOf(agents);
            fetchedAt = System.currentTimeMillis();
            log.info("[Agent] 已保存智能体配置（共 {} 个）", agents.size());
        } catch (io.agentscope.examples.payroll.domain.PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new io.agentscope.examples.payroll.domain.PayrollException(
                    "序列化智能体配置失败：" + e.getMessage());
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
                        now),
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
                        now),
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
                        now));
    }
}
