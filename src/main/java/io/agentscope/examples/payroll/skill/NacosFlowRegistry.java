package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
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
 * 智能体流程注册表：流程列表存储于 Nacos（dataId=payroll-flows.json），
 * 全局默认流程存储在 payroll-flow-settings.json，读路径带 TTL 缓存。
 *
 * <p>Nacos 未启用或配置缺失时回退内置“薪酬标准流程”；内置流程未激活时对话仍走既有链路。
 */
public final class NacosFlowRegistry {

    public static final String DATA_ID = "payroll-flows.json";
    public static final String SETTINGS_DATA_ID = "payroll-flow-settings.json";
    public static final String DEFAULT_FLOW_ID = "payroll-default";

    private static final Logger log = LoggerFactory.getLogger(NacosFlowRegistry.class);

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final long ttlMillis;

    private volatile List<FlowDefinition> cache;
    private volatile long fetchedAt;

    private volatile String cachedActiveFlowId;
    private volatile long settingsFetchedAt;

    public NacosFlowRegistry() {
        this(NacosSkillSettings.fromEnvironment());
    }

    NacosFlowRegistry(NacosSkillSettings settings) {
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

    public List<FlowDefinition> list() {
        long now = System.currentTimeMillis();
        List<FlowDefinition> snapshot = cache;
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

    public FlowDefinition find(String id) {
        return list().stream()
                .filter(flow -> flow.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** 全局默认流程 id；未启用或未设置时返回空串。 */
    public String activeFlowId() {
        if (!settings.enabled()) {
            return "";
        }
        long now = System.currentTimeMillis();
        String snapshot = cachedActiveFlowId;
        if (snapshot != null && now - settingsFetchedAt < ttlMillis) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedActiveFlowId;
            if (snapshot != null && now - settingsFetchedAt < ttlMillis) {
                return snapshot;
            }
            snapshot = loadActiveFlowId(snapshot);
            cachedActiveFlowId = snapshot;
            settingsFetchedAt = now;
            return snapshot;
        }
    }

    /** 当前全局默认流程；未设置或已被删除时返回 null，调用方回退既有链路。 */
    public FlowDefinition activeFlow() {
        String id = activeFlowId();
        if (id.isBlank()) {
            return null;
        }
        FlowDefinition flow = find(id);
        if (flow == null) {
            log.warn("[流程编排] 默认流程 {} 不存在，回退既有对话链路", id);
        }
        return flow;
    }

    public synchronized void setActiveFlow(String id) {
        requireWritable();
        FlowDefinition flow = find(id);
        if (flow == null) {
            throw new PayrollException("流程不存在：" + id);
        }
        publishSettings(id);
        log.info("[流程编排] 默认流程已设置为 {}（{}）", flow.name(), id);
    }

    public synchronized FlowDefinition save(FlowDefinition input, String operator) {
        requireWritable();
        List<FlowDefinition> flows = new ArrayList<>(list());
        LocalDateTime now = LocalDateTime.now();
        int index = -1;
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).id().equals(input.id())) {
                index = i;
                break;
            }
        }
        FlowDefinition saved;
        if (index >= 0) {
            FlowDefinition existing = flows.get(index);
            saved = new FlowDefinition(
                    existing.id(),
                    input.name(),
                    input.description(),
                    input.nodes(),
                    input.edges(),
                    existing.createdBy(),
                    existing.createdAt(),
                    now);
            flows.set(index, saved);
        } else {
            String id = input.id() == null || input.id().isBlank()
                    ? "flow-" + UUID.randomUUID().toString().substring(0, 8)
                    : input.id();
            saved = new FlowDefinition(
                    id,
                    input.name(),
                    input.description(),
                    input.nodes(),
                    input.edges(),
                    operator,
                    now,
                    now);
            flows.add(saved);
        }
        persist(flows);
        return saved;
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        if (DEFAULT_FLOW_ID.equals(id)) {
            throw new PayrollException("内置标准流程不能删除");
        }
        if (id.equals(activeFlowId())) {
            throw new PayrollException("默认流程不能删除，请先把其他流程设为默认");
        }
        List<FlowDefinition> flows = new ArrayList<>(list());
        boolean removed = flows.removeIf(flow -> flow.id().equals(id));
        if (removed) {
            persist(flows);
        }
        return removed;
    }

    private void publishSettings(String id) {
        try {
            String content = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("activeFlowId", id));
            if (!client.publish(SETTINGS_DATA_ID, content)) {
                throw new PayrollException(
                        "写入 Nacos 配置 " + SETTINGS_DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
            cachedActiveFlowId = id;
            settingsFetchedAt = System.currentTimeMillis();
        } catch (PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new PayrollException("序列化默认流程配置失败：" + e.getMessage());
        }
    }

    /** 读取失败时沿用上次值，避免临时抖动让默认流程“消失”并回退到薪酬链路。 */
    private String loadActiveFlowId(String fallback) {
        Optional<String> body = client.fetch(SETTINGS_DATA_ID);
        if (body.isEmpty()) {
            if (fallback != null && !fallback.isBlank()) {
                log.warn("[流程编排] 读取 {} 失败，沿用上次默认流程 {}", SETTINGS_DATA_ID, fallback);
                return fallback;
            }
            return "";
        }
        try {
            return json.readTree(body.get()).path("activeFlowId").asText("").trim();
        } catch (Exception e) {
            if (fallback != null && !fallback.isBlank()) {
                log.warn(
                        "[流程编排] 解析 {} 失败，沿用上次默认流程 {}：{}",
                        SETTINGS_DATA_ID,
                        fallback,
                        e.getMessage());
                return fallback;
            }
            log.warn("[流程编排] 解析 Nacos 配置 {} 失败，按未设置处理：{}",
                    SETTINGS_DATA_ID, e.getMessage());
            return "";
        }
    }

    private List<FlowDefinition> load(List<FlowDefinition> previous) {
        if (!settings.enabled()) {
            return List.of(builtinFlow());
        }
        Optional<String> body = client.fetch(DATA_ID);
        if (body.isEmpty()) {
            if (previous != null && !previous.isEmpty()) {
                log.warn(
                        "[流程编排] Nacos 读取 {} 失败或内容为空，沿用上次成功加载的 {} 条流程定义",
                        DATA_ID,
                        previous.size());
                return previous;
            }
            log.info("[流程编排] Nacos 未提供 {}，使用内置标准流程", DATA_ID);
            return List.of(builtinFlow());
        }
        try {
            List<FlowDefinition> flows =
                    json.readValue(
                            body.get(),
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, FlowDefinition.class));
            log.info("[流程编排] 从 Nacos 加载 {} 条流程定义", flows.size());
            return List.copyOf(flows);
        } catch (Exception e) {
            if (previous != null && !previous.isEmpty()) {
                log.warn(
                        "[流程编排] 解析 Nacos 配置 {} 失败，沿用上次成功加载的 {} 条流程定义：{}",
                        DATA_ID,
                        previous.size(),
                        e.getMessage());
                return previous;
            }
            log.warn("[流程编排] 解析 Nacos 配置 {} 失败，回退内置标准流程：{}", DATA_ID, e.getMessage());
            return List.of(builtinFlow());
        }
    }

    private void persist(List<FlowDefinition> flows) {
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(flows);
            if (!client.publish(DATA_ID, content)) {
                throw new PayrollException(
                        "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
            cache = List.copyOf(flows);
            fetchedAt = System.currentTimeMillis();
            log.info("[流程编排] 已保存流程配置（共 {} 条）", flows.size());
        } catch (PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new PayrollException("序列化流程配置失败：" + e.getMessage());
        }
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法保存流程定义");
        }
    }

    /** 内置标准流程：保留“查询类问题跳过验证与核算”的既有语义。 */
    public static FlowDefinition builtinFlow() {
        LocalDateTime now = LocalDateTime.now();
        List<FlowNode> nodes = List.of(
                new FlowNode("start", FlowNode.TYPE_START, "开始", null, null, null, 60.0, 180.0),
                new FlowNode(
                        "calculator",
                        FlowNode.TYPE_AGENT,
                        "① 计算智能体",
                        RoleSkills.CALCULATOR,
                        null,
                        null,
                        300.0,
                        180.0),
                new FlowNode(
                        "check",
                        FlowNode.TYPE_CONDITION,
                        "是否有核算对象",
                        null,
                        FlowNode.CONDITION_HAS_TARGETS,
                        null,
                        560.0,
                        180.0),
                new FlowNode(
                        "validator",
                        FlowNode.TYPE_AGENT,
                        "② 验证智能体",
                        RoleSkills.VALIDATOR,
                        null,
                        null,
                        820.0,
                        70.0),
                new FlowNode(
                        "accountant",
                        FlowNode.TYPE_AGENT,
                        "③ 核算智能体",
                        RoleSkills.ACCOUNTANT,
                        null,
                        null,
                        1080.0,
                        70.0),
                new FlowNode("end", FlowNode.TYPE_END, "结束", null, null, null, 1340.0, 180.0));
        List<FlowEdge> edges = List.of(
                new FlowEdge("e1", "start", "calculator", null),
                new FlowEdge("e2", "calculator", "check", null),
                new FlowEdge("e3", "check", "validator", FlowEdge.HANDLE_TRUE),
                new FlowEdge("e4", "validator", "accountant", null),
                new FlowEdge("e5", "accountant", "end", null),
                new FlowEdge("e6", "check", "end", FlowEdge.HANDLE_FALSE));
        return new FlowDefinition(
                DEFAULT_FLOW_ID,
                "薪酬标准流程",
                "计算 → 判断是否有核算对象 → 验证 → 核算；查询类问题直接返回计算记录",
                nodes,
                edges,
                "system",
                now,
                now);
    }
}
