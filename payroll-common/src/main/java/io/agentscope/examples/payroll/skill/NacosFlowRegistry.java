package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.domain.FlowEdge;
import io.agentscope.examples.payroll.domain.FlowNode;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能体流程注册表：流程列表存储于 Nacos（dataId=payroll-flows.json）；
 * 全局默认流程与核算模式合并存放于 payroll-runtime-settings.json，读路径带 TTL 缓存。
 *
 * <p>写路径绕过 TTL 重读并校验内容指纹；运行时设置与智能体注册表共享同一实例，
 * 流程与核算智能体互斥由单份文档保证。
 * Nacos 未启用或配置缺失时回退内置“薪酬标准流程”；内置流程未激活时对话仍走既有链路。
 */
public final class NacosFlowRegistry {

    public static final String DATA_ID = "payroll-flows.json";
    public static final String DEFAULT_FLOW_ID = "payroll-default";

    private static final Logger log = LoggerFactory.getLogger(NacosFlowRegistry.class);

    /** 配置快照：items 为解析结果，revision 为原始文档内容指纹。 */
    public record Snapshot<T>(List<T> items, String revision) {
    }

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final NacosRuntimeSettings runtime;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final long ttlMillis;

    private volatile Snapshot<FlowDefinition> cache;
    private volatile long fetchedAt;

    public NacosFlowRegistry() {
        this(NacosSkillSettings.fromEnvironment());
    }

    NacosFlowRegistry(NacosSkillSettings settings) {
        this(settings, new NacosRuntimeSettings(settings));
    }

    /** 与智能体注册表共享同一份运行时设置缓存，避免两个实例各自读到旧模式。 */
    public NacosFlowRegistry(NacosSkillSettings settings, NacosRuntimeSettings runtime) {
        this.settings = settings;
        this.runtime = runtime;
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
        return snapshot().items();
    }

    /** 当前 flows 文档内容指纹（读路径，TTL 内缓存）。 */
    public String revision() {
        return snapshot().revision();
    }

    /** 运行时设置文档指纹；未启用时为空指纹。 */
    public String settingsRevision() {
        return settings.enabled() ? runtime.revision() : ConfigRevision.ABSENT;
    }

    public FlowDefinition find(String id) {
        return list().stream()
                .filter(flow -> flow.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** 全局默认流程 id；仅 FLOW 模式非空。 */
    public String activeFlowId() {
        return settings.enabled() ? runtime.activeFlowId() : "";
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
        setActiveFlow(id, null);
    }

    /** @param expectedRevision 运行时设置文档指纹；null/空白跳过校验（滚动发布兼容） */
    public synchronized void setActiveFlow(String id, String expectedRevision) {
        requireWritable();
        FlowDefinition flow = find(id);
        if (flow == null) {
            throw new PayrollException("流程不存在：" + id);
        }
        runtime.activateFlow(id, expectedRevision);
        log.info("[流程编排] 默认流程已设置为 {}（{}）", flow.name(), id);
    }

    /** 取消全局默认流程：对话回到单个核算智能体或内置三段流水线。 */
    public synchronized void clearActiveFlow() {
        clearActiveFlow(null);
    }

    public synchronized void clearActiveFlow(String expectedRevision) {
        requireWritable();
        runtime.clearFlow(expectedRevision);
        log.info("[流程编排] 已取消全局默认流程，对话回到智能体链路");
    }

    public synchronized FlowDefinition save(FlowDefinition input, String operator) {
        return save(input, operator, null);
    }

    /** @param expectedRevision flows 文档指纹；null/空白跳过校验（滚动发布兼容） */
    public synchronized FlowDefinition save(
            FlowDefinition input, String operator, String expectedRevision) {
        requireWritable();
        Snapshot<FlowDefinition> current = reloadForWrite();
        requireRevision(current.revision(), expectedRevision, DATA_ID);
        List<FlowDefinition> flows = new ArrayList<>(current.items());
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
                    now,
                    // 规格 6.2：未提交能力声明时保留已有声明，避免旧画布保存后静默丢能力。
                    input.capabilities().isEmpty()
                            ? existing.capabilities()
                            : input.capabilities());
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
                    now,
                    input.capabilities());
            flows.add(saved);
        }
        persist(flows);
        return saved;
    }

    public synchronized boolean delete(String id) {
        return delete(id, null);
    }

    public synchronized boolean delete(String id, String expectedRevision) {
        requireWritable();
        Snapshot<FlowDefinition> current = reloadForWrite();
        requireRevision(current.revision(), expectedRevision, DATA_ID);
        if (DEFAULT_FLOW_ID.equals(id)) {
            throw new PayrollException("内置标准流程不能删除");
        }
        if (id.equals(activeFlowId())) {
            throw new PayrollException("默认流程不能删除，请先取消默认流程或把其他流程设为默认");
        }
        List<FlowDefinition> flows = new ArrayList<>(current.items());
        boolean removed = flows.removeIf(flow -> flow.id().equals(id));
        if (removed) {
            persist(flows);
        }
        return removed;
    }

    private Snapshot<FlowDefinition> snapshot() {
        long now = System.currentTimeMillis();
        Snapshot<FlowDefinition> snapshot = cache;
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

    /** 读取失败时沿用上次快照，避免临时抖动让流程列表“消失”。 */
    private Snapshot<FlowDefinition> load(Snapshot<FlowDefinition> previous) {
        if (!settings.enabled()) {
            return new Snapshot<>(List.of(builtinFlow()), ConfigRevision.ABSENT);
        }
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case ERROR -> {
                if (previous != null && !previous.items().isEmpty()) {
                    log.warn(
                            "[流程编排] Nacos 读取 {} 失败，沿用上次成功加载的 {} 条流程定义：{}",
                            DATA_ID,
                            previous.items().size(),
                            fetched.message());
                    return previous;
                }
                log.warn("[流程编排] Nacos 读取 {} 失败，回退内置标准流程：{}", DATA_ID, fetched.message());
                return new Snapshot<>(List.of(builtinFlow()), ConfigRevision.ABSENT);
            }
            case ABSENT -> {
                if (previous != null && !previous.items().isEmpty()) {
                    log.warn(
                            "[流程编排] Nacos 未提供 {}，沿用上次成功加载的 {} 条流程定义",
                            DATA_ID,
                            previous.items().size());
                    return new Snapshot<>(previous.items(), ConfigRevision.ABSENT);
                }
                log.info("[流程编排] Nacos 未提供 {}，使用内置标准流程", DATA_ID);
                return new Snapshot<>(List.of(builtinFlow()), ConfigRevision.ABSENT);
            }
            default -> {
                String raw = fetched.entry().content();
                try {
                    return new Snapshot<>(parseFlows(raw), ConfigRevision.revisionOf(raw));
                } catch (Exception e) {
                    if (previous != null && !previous.items().isEmpty()) {
                        log.warn(
                                "[流程编排] 解析 Nacos 配置 {} 失败，沿用上次成功加载的 {} 条流程定义：{}",
                                DATA_ID,
                                previous.items().size(),
                                e.getMessage());
                        return previous;
                    }
                    log.warn("[流程编排] 解析 Nacos 配置 {} 失败，回退内置标准流程：{}",
                            DATA_ID, e.getMessage());
                    return new Snapshot<>(List.of(builtinFlow()), ConfigRevision.ABSENT);
                }
            }
        }
    }

    /** 写前重读：读取失败或解析失败一律 503，禁止用旧数据写回。 */
    private Snapshot<FlowDefinition> reloadForWrite() {
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case OK -> {
                String raw = fetched.entry().content();
                try {
                    return new Snapshot<>(parseFlows(raw), ConfigRevision.revisionOf(raw));
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ABSENT -> {
                return new Snapshot<>(List.of(builtinFlow()), ConfigRevision.ABSENT);
            }
            default -> throw new PayrollUnavailableException(
                    "读取 " + DATA_ID + " 失败：" + fetched.message());
        }
    }

    private List<FlowDefinition> parseFlows(String raw) throws Exception {
        List<FlowDefinition> flows =
                json.readValue(
                        raw,
                        json.getTypeFactory()
                                .constructCollectionType(List.class, FlowDefinition.class));
        if (flows == null) {
            throw new IllegalStateException("配置根节点不是 JSON 数组");
        }
        log.info("[流程编排] 从 Nacos 加载 {} 条流程定义", flows.size());
        return List.copyOf(flows);
    }

    private void persist(List<FlowDefinition> flows) {
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(flows);
            if (!client.publish(DATA_ID, content)) {
                throw new PayrollException(
                        "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
            cache = new Snapshot<>(List.copyOf(flows), ConfigRevision.revisionOf(content));
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

    /** flows 文档的并发写入校验；运行时设置文档由 {@link NacosRuntimeSettings} 校验。 */
    private void requireRevision(String current, String expected, String dataId) {
        if (expected == null || expected.isBlank()) {
            log.warn("[流程编排] 写入 {} 未携带 revision，跳过并发校验（revision_missing=true）", dataId);
            return;
        }
        if (!current.equals(expected)) {
            throw new PayrollConflictException("配置已被他人修改，请刷新后重试", current);
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
