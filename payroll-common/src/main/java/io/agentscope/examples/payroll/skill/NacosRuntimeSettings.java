package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时全局设置：核算模式、默认流程、核算智能体与写操作确认合并为一份 Nacos 文档
 * （dataId=payroll-runtime-settings.json），切换模式只需一次 publish。
 *
 * <p>读取优先新文档；新文档缺失时回退旧的两份设置文档
 * （payroll-agent-settings.json / payroll-flow-settings.json）。写入只落新文档，
 * 首次写入会把旧文档内容迁入同一份根节点，旧文档保持只读，供回滚一个版本周期。
 *
 * <p>mode 与 ids 保持一致：FLOW 时只带 flowId，AGENT 时只带 agentId，
 * PIPELINE 两者皆空，避免流程与智能体同时生效。
 */
public final class NacosRuntimeSettings {

    public static final String DATA_ID = "payroll-runtime-settings.json";
    public static final String LEGACY_AGENT_DATA_ID = "payroll-agent-settings.json";
    public static final String LEGACY_FLOW_DATA_ID = "payroll-flow-settings.json";

    public static final String MODE_FLOW = "FLOW";
    public static final String MODE_AGENT = "AGENT";
    public static final String MODE_PIPELINE = "PIPELINE";

    private static final Logger log = LoggerFactory.getLogger(NacosRuntimeSettings.class);

    /** 规范化快照：与 mode 无关的 id 一律为空串，调用方无需再判断互斥。 */
    public record Snapshot(
            String mode, String agentId, String flowId, Boolean actionConfirm, String revision) {
    }

    /** 写基线：原始根节点 + 当前快照 + 原始内容指纹。 */
    private record Base(ObjectNode root, Snapshot snapshot) {
    }

    private record LegacyValues(String agentId, String flowId, Boolean actionConfirm) {
    }

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final long ttlMillis;

    private volatile Snapshot cache;
    private volatile long fetchedAt;

    public NacosRuntimeSettings() {
        this(NacosSkillSettings.fromEnvironment());
    }

    public NacosRuntimeSettings(NacosSkillSettings settings) {
        this.settings = settings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** 新文档 → 旧文档 → 上次成功快照；Nacos 未启用时固定 PIPELINE。 */
    public Snapshot snapshot() {
        if (!settings.enabled()) {
            return normalize(MODE_PIPELINE, "", "", null, ConfigRevision.ABSENT);
        }
        long now = System.currentTimeMillis();
        Snapshot snapshot = cache;
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

    /** 新文档内容指纹；未迁移（新文档缺失）时等同旧文档基线。 */
    public String revision() {
        return snapshot().revision();
    }

    /** 当前生效的核算智能体 id；仅 AGENT 模式非空。 */
    public String activeAgentId() {
        Snapshot snapshot = snapshot();
        return MODE_AGENT.equals(snapshot.mode()) ? snapshot.agentId() : "";
    }

    /** 当前生效的默认流程 id；仅 FLOW 模式非空。 */
    public String activeFlowId() {
        Snapshot snapshot = snapshot();
        return MODE_FLOW.equals(snapshot.mode()) ? snapshot.flowId() : "";
    }

    /** 写操作确认覆盖值；未配置时为空，由调用方回退环境变量。 */
    public Optional<Boolean> actionConfirmOverride() {
        return Optional.ofNullable(snapshot().actionConfirm());
    }

    /** 激活核算智能体：同一份文档内清空默认流程。 */
    public synchronized void activateAgent(String agentId, String expectedRevision) {
        requireWritable();
        Base base = loadForWrite();
        requireRevision(base.snapshot().revision(), expectedRevision);
        publish(base.root(), root -> {
            root.put("mode", MODE_AGENT);
            root.put("agentId", agentId == null ? "" : agentId.trim());
            root.put("flowId", "");
        });
        log.info("[设置] 核算智能体已设置为 {}（{}）", agentId, DATA_ID);
    }

    /** 取消核算智能体；FLOW 模式下不动默认流程。 */
    public synchronized void clearAgent(String expectedRevision) {
        requireWritable();
        Base base = loadForWrite();
        if (!MODE_AGENT.equals(base.snapshot().mode())) {
            return;
        }
        requireRevision(base.snapshot().revision(), expectedRevision);
        publish(base.root(), root -> {
            root.put("mode", MODE_PIPELINE);
            root.put("agentId", "");
            root.put("flowId", "");
        });
        log.info("[设置] 已取消核算智能体（{}）", DATA_ID);
    }

    /** 激活默认流程：同一份文档内清空核算智能体。 */
    public synchronized void activateFlow(String flowId, String expectedRevision) {
        requireWritable();
        Base base = loadForWrite();
        requireRevision(base.snapshot().revision(), expectedRevision);
        publish(base.root(), root -> {
            root.put("mode", MODE_FLOW);
            root.put("flowId", flowId == null ? "" : flowId.trim());
            root.put("agentId", "");
        });
        log.info("[设置] 默认流程已设置为 {}（{}）", flowId, DATA_ID);
    }

    /** 取消默认流程；AGENT 模式下不动核算智能体。 */
    public synchronized void clearFlow(String expectedRevision) {
        requireWritable();
        Base base = loadForWrite();
        if (!MODE_FLOW.equals(base.snapshot().mode())) {
            return;
        }
        requireRevision(base.snapshot().revision(), expectedRevision);
        publish(base.root(), root -> {
            root.put("mode", MODE_PIPELINE);
            root.put("agentId", "");
            root.put("flowId", "");
        });
        log.info("[设置] 已取消默认流程（{}）", DATA_ID);
    }

    /** 写操作确认开关：只改 actionConfirm，保留当前模式。 */
    public synchronized void setActionConfirm(boolean enabled, String expectedRevision) {
        requireWritable();
        Base base = loadForWrite();
        requireRevision(base.snapshot().revision(), expectedRevision);
        publish(base.root(), root -> root.put("actionConfirm", enabled));
        log.info("[设置] 写操作确认已{}（{}）", enabled ? "开启" : "关闭", DATA_ID);
    }

    private Snapshot load(Snapshot previous) {
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case OK -> {
                try {
                    ObjectNode root = requireObject(fetched.entry().content());
                    return parse(root, ConfigRevision.revisionOf(fetched.entry().content()));
                } catch (Exception e) {
                    log.warn("[设置] 解析 {} 失败，回退旧设置文档：{}", DATA_ID, e.getMessage());
                    return loadLegacy();
                }
            }
            case ABSENT -> {
                return loadLegacy();
            }
            default -> {
                if (previous != null) {
                    log.warn("[设置] 读取 {} 失败，沿用上次设置：{}", DATA_ID, fetched.message());
                    return previous;
                }
                log.warn("[设置] 读取 {} 失败，回退旧设置文档：{}", DATA_ID, fetched.message());
                return loadLegacy();
            }
        }
    }

    /** 旧的两份设置文档只读回退；任一读取失败只记日志，不阻塞对话。 */
    private Snapshot loadLegacy() {
        String agentId = "";
        Boolean confirm = null;
        NacosConfigClient.FetchResult agent = client.fetchResult(LEGACY_AGENT_DATA_ID);
        if (agent.status() == NacosConfigClient.FetchResult.Status.OK) {
            try {
                JsonNode root = json.readTree(agent.entry().content());
                agentId = root.path("calculationAgentId").asText("").trim();
                JsonNode node = root.path("actionConfirm");
                confirm = node.isBoolean() ? node.asBoolean() : null;
            } catch (Exception e) {
                log.warn("[设置] 解析旧设置 {} 失败：{}", LEGACY_AGENT_DATA_ID, e.getMessage());
            }
        } else if (agent.status() == NacosConfigClient.FetchResult.Status.ERROR) {
            log.warn("[设置] 读取旧设置 {} 失败：{}", LEGACY_AGENT_DATA_ID, agent.message());
        }
        String flowId = "";
        NacosConfigClient.FetchResult flow = client.fetchResult(LEGACY_FLOW_DATA_ID);
        if (flow.status() == NacosConfigClient.FetchResult.Status.OK) {
            try {
                flowId = json.readTree(flow.entry().content()).path("activeFlowId").asText("").trim();
            } catch (Exception e) {
                log.warn("[设置] 解析旧设置 {} 失败：{}", LEGACY_FLOW_DATA_ID, e.getMessage());
            }
        } else if (flow.status() == NacosConfigClient.FetchResult.Status.ERROR) {
            log.warn("[设置] 读取旧设置 {} 失败：{}", LEGACY_FLOW_DATA_ID, flow.message());
        }
        return normalize("", agentId, flowId, confirm, ConfigRevision.ABSENT);
    }

    /** 绕过 TTL 读取写基线：新文档不存在时把旧文档内容迁入同一份根节点。 */
    private Base loadForWrite() {
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case OK -> {
                try {
                    String raw = fetched.entry().content();
                    ObjectNode root = requireObject(raw);
                    return new Base(root, parse(root, ConfigRevision.revisionOf(raw)));
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ABSENT -> {
                LegacyValues legacy = readLegacyForWrite();
                ObjectNode root = legacyRoot(legacy);
                return new Base(
                        root,
                        parse(root, ConfigRevision.ABSENT));
            }
            default -> throw new PayrollUnavailableException(
                    "读取 " + DATA_ID + " 失败：" + fetched.message());
        }
    }

    /** 迁移基线：旧文档读失败直接 503，禁止用空基线覆盖已有设置。 */
    private LegacyValues readLegacyForWrite() {
        String agentId = "";
        Boolean confirm = null;
        NacosConfigClient.FetchResult agent = client.fetchResult(LEGACY_AGENT_DATA_ID);
        switch (agent.status()) {
            case OK -> {
                try {
                    JsonNode root = json.readTree(agent.entry().content());
                    agentId = root.path("calculationAgentId").asText("").trim();
                    JsonNode node = root.path("actionConfirm");
                    confirm = node.isBoolean() ? node.asBoolean() : null;
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + LEGACY_AGENT_DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ERROR -> throw new PayrollUnavailableException(
                    "读取 " + LEGACY_AGENT_DATA_ID + " 失败：" + agent.message());
            default -> {
            }
        }
        String flowId = "";
        NacosConfigClient.FetchResult flow = client.fetchResult(LEGACY_FLOW_DATA_ID);
        switch (flow.status()) {
            case OK -> {
                try {
                    flowId = json.readTree(flow.entry().content()).path("activeFlowId").asText("").trim();
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + LEGACY_FLOW_DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ERROR -> throw new PayrollUnavailableException(
                    "读取 " + LEGACY_FLOW_DATA_ID + " 失败：" + flow.message());
            default -> {
            }
        }
        return new LegacyValues(agentId, flowId, confirm);
    }

    private ObjectNode legacyRoot(LegacyValues legacy) {
        ObjectNode root = json.createObjectNode();
        if (legacy.flowId() != null && !legacy.flowId().isBlank()) {
            root.put("mode", MODE_FLOW);
            root.put("flowId", legacy.flowId());
            root.put("agentId", "");
        } else if (legacy.agentId() != null && !legacy.agentId().isBlank()) {
            root.put("mode", MODE_AGENT);
            root.put("agentId", legacy.agentId());
            root.put("flowId", "");
        } else {
            root.put("mode", MODE_PIPELINE);
            root.put("agentId", "");
            root.put("flowId", "");
        }
        if (legacy.actionConfirm() != null) {
            root.put("actionConfirm", legacy.actionConfirm());
        }
        return root;
    }

    private void publish(ObjectNode root, Consumer<ObjectNode> mutator) {
        mutator.accept(root);
        normalizeRoot(root);
        String content;
        try {
            content = json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new PayrollException("序列化运行时设置失败：" + e.getMessage());
        }
        if (!client.publish(DATA_ID, content)) {
            throw new PayrollException(
                    "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
        }
        cache = parse(root, ConfigRevision.revisionOf(content));
        fetchedAt = System.currentTimeMillis();
    }

    private Snapshot parse(ObjectNode root, String revision) {
        String agentId = root.path("agentId").asText("").trim();
        String flowId = root.path("flowId").asText("").trim();
        JsonNode confirm = root.path("actionConfirm");
        Boolean actionConfirm = confirm.isBoolean() ? confirm.asBoolean() : null;
        return normalize(root.path("mode").asText(""), agentId, flowId, actionConfirm, revision);
    }

    private static ObjectNode requireObject(String raw) throws Exception {
        JsonNode parsed = JsonMapper.builder().build().readTree(raw);
        if (!(parsed instanceof ObjectNode root)) {
            throw new IllegalStateException("配置根节点不是 JSON 对象");
        }
        return root;
    }

    /** mode 优先，缺失或与 ids 不一致时按 ids 推断，非法组合降级为 PIPELINE。 */
    private static Snapshot normalize(
            String mode,
            String agentId,
            String flowId,
            Boolean actionConfirm,
            String revision) {
        String agent = agentId == null ? "" : agentId.trim();
        String flow = flowId == null ? "" : flowId.trim();
        String resolved = mode == null ? "" : mode.trim().toUpperCase();
        if (!MODE_FLOW.equals(resolved) && !MODE_AGENT.equals(resolved)) {
            resolved = flow.isEmpty() ? (agent.isEmpty() ? MODE_PIPELINE : MODE_AGENT) : MODE_FLOW;
        }
        if (MODE_FLOW.equals(resolved)) {
            return flow.isEmpty()
                    ? new Snapshot(MODE_PIPELINE, "", "", actionConfirm, revision)
                    : new Snapshot(MODE_FLOW, "", flow, actionConfirm, revision);
        }
        if (MODE_AGENT.equals(resolved)) {
            return agent.isEmpty()
                    ? new Snapshot(MODE_PIPELINE, "", "", actionConfirm, revision)
                    : new Snapshot(MODE_AGENT, agent, "", actionConfirm, revision);
        }
        return new Snapshot(MODE_PIPELINE, "", "", actionConfirm, revision);
    }

    private static void normalizeRoot(ObjectNode root) {
        Snapshot snapshot =
                normalize(
                        root.path("mode").asText(""),
                        root.path("agentId").asText(""),
                        root.path("flowId").asText(""),
                        null,
                        "");
        root.put("mode", snapshot.mode());
        root.put("agentId", snapshot.agentId());
        root.put("flowId", snapshot.flowId());
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法保存运行时设置");
        }
    }

    private void requireRevision(String current, String expected) {
        if (expected == null || expected.isBlank()) {
            log.warn("[设置] 写入 {} 未携带 revision，跳过并发校验（revision_missing=true)", DATA_ID);
            return;
        }
        if (!current.equals(expected)) {
            throw new PayrollConflictException("配置已被他人修改，请刷新后重试", current);
        }
    }
}
