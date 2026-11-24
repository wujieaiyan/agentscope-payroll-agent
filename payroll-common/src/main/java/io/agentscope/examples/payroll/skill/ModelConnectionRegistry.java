package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.model.AgentModelSettings;
import io.agentscope.examples.payroll.model.ModelCatalog;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelConnections;
import io.agentscope.examples.payroll.model.ModelDefaults;
import io.agentscope.examples.payroll.devteam.DevModelSettings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型连接注册表：连接元数据整份存 Nacos（{@code payroll-models.json}），读路径带 TTL 缓存；
 * 环境变量派生连接只读，始终追加在列表末尾。
 *
 * <p>Nacos publish 不是 compare-and-swap：revision 校验只能降低并发覆盖概率，
 * 语义为「最后写入者胜」。
 */
public final class ModelConnectionRegistry {

    public static final String DATA_ID = "payroll-models.json";

    private static final Logger log = LoggerFactory.getLogger(ModelConnectionRegistry.class);

    private static final ModelConnectionRegistry SHARED =
            new ModelConnectionRegistry(NacosSkillSettings.fromEnvironment());

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final ObjectMapper json =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
    private final long ttlMillis;

    private volatile ModelCatalog cache;
    private volatile long fetchedAt;

    public static ModelConnectionRegistry shared() {
        return SHARED;
    }

    public ModelConnectionRegistry(NacosSkillSettings settings) {
        this.settings = settings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = longEnv("PAYROLL_MODELS_TTL_SECONDS", 30L) * 1000L;
    }

    public boolean writable() {
        return settings.enabled();
    }

    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "环境变量";
    }

    /** 管理台视图：持久化连接 + 环境变量只读连接。 */
    public ModelCatalog catalog() {
        ModelCatalog stored = stored();
        List<ModelConnection> merged = new ArrayList<>();
        for (ModelConnection connection : stored.connections()) {
            if (!isEnvId(connection.id())) {
                merged.add(connection);
            }
        }
        merged.add(ModelConnections.envControl());
        ModelConnections.envCoding().ifPresent(merged::add);
        return new ModelCatalog(
                stored.version(), stored.revision(), stored.defaults(), merged);
    }

    public List<ModelConnection> connections() {
        return catalog().connections();
    }

    public long revision() {
        return catalog().revision();
    }

    public Optional<ModelConnection> find(String id) {
        return catalog().connection(id);
    }

    /** 故障切换备用连接：按目录顺序取第一个已启用、且不是主连接的连接。 */
    public Optional<ModelConnection> fallback(ModelConnection primary) {
        return fallback(primary, null);
    }

    /** {@code filter} 用于附加能力约束（如编码面必须支持 tools 且上下文足够）。 */
    public Optional<ModelConnection> fallback(
            ModelConnection primary, Predicate<ModelConnection> filter) {
        if (primary == null) {
            return Optional.empty();
        }
        for (ModelConnection connection : connections()) {
            if (!connection.isEnabled() || connection.id().equals(primary.id())) {
                continue;
            }
            if (filter != null && !filter.test(connection)) {
                continue;
            }
            return Optional.of(connection);
        }
        return Optional.empty();
    }

    public boolean envCodingLocked() {
        return ModelConnections.envCodingConfigured();
    }

    /** 控制面：智能体连接 > 页面默认 > PAYROLL_MODEL 映射。缺失即报错，不静默回落。 */
    public ModelConnection resolveControl(AgentModelSettings override) {
        ModelCatalog stored = stored();
        if (override != null && !override.connectionId().isBlank()) {
            ModelConnection base =
                    available(
                            stored,
                            override.connectionId(),
                            "智能体绑定的模型连接「" + override.connectionId() + "」");
            return base.overlay(override);
        }
        ModelConnection base;
        String preferred = stored.defaults().control();
        if (preferred.isBlank()) {
            base = ModelConnections.envControl();
        } else {
            base = available(stored, preferred, "控制面默认连接「" + preferred + "」");
        }
        return override == null ? base : base.overlay(override);
    }

    /** 轻量面：页面 utility 默认 > 控制面默认 > PAYROLL_MODEL 映射。 */
    public ModelConnection resolveUtility() {
        ModelCatalog stored = stored();
        String preferred = stored.defaults().utility();
        if (preferred.isBlank()) {
            return resolveControl(AgentModelSettings.none());
        }
        return available(stored, preferred, "轻量面默认连接「" + preferred + "」");
    }

    /** 编码面：PAYROLL_DEV_* 恒定优先；未设置时才读取页面 coding 默认。 */
    public Optional<ModelConnection> resolveCoding() {
        Optional<ModelConnection> envCoding = ModelConnections.envCoding();
        if (envCoding.isPresent()) {
            return envCoding;
        }
        ModelCatalog stored = stored();
        String preferred = stored.defaults().coding();
        if (preferred.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(available(stored, preferred, "编码面默认连接「" + preferred + "」"));
    }

    public synchronized ModelCatalog saveConnection(
            ModelConnection input, long expectedRevision, String operator) {
        requireWritable();
        if (input == null) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION, "缺少连接配置");
        }
        if (isEnvId(input.id())) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "连接 ID「" + input.id() + "」保留给环境变量映射，请使用其它 ID");
        }
        ModelConnection validated = input.validated();
        ModelCatalog current = reloadStrict();
        requireRevision(current, expectedRevision);
        if (!validated.enabled() && isDefaultOf(current.defaults(), validated.id())) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "连接「" + validated.id() + "」仍是使用面默认值，请先切换默认连接再停用");
        }
        ModelConnection saved =
                new ModelConnection(
                        validated.id(),
                        validated.name(),
                        validated.provider(),
                        validated.baseUrl(),
                        validated.model(),
                        validated.apiKeyEnv(),
                        validated.credentialRef(),
                        validated.contextWindow(),
                        validated.maxTokens(),
                        validated.temperature(),
                        validated.timeoutSeconds(),
                        validated.capabilities(),
                        validated.enabled(),
                        LocalDateTime.now(),
                        operator,
                        ModelConnection.ORIGIN_STORED);
        List<ModelConnection> connections = new ArrayList<>();
        boolean replaced = false;
        boolean duplicate = false;
        for (ModelConnection connection : current.connections()) {
            if (connection.id().equals(saved.id())) {
                if (replaced) {
                    duplicate = true;
                }
                connections.add(saved);
                replaced = true;
            } else {
                connections.add(connection);
            }
        }
        if (duplicate) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "连接 ID 重复：" + saved.id());
        }
        if (!replaced) {
            connections.add(saved);
        }
        return persist(
                new ModelCatalog(
                        ModelCatalog.CURRENT_VERSION,
                        current.revision() + 1,
                        current.defaults(),
                        connections));
    }

    public synchronized ModelCatalog deleteConnection(
            String id, long expectedRevision, String operator) {
        requireWritable();
        ModelCatalog current = reloadStrict();
        requireRevision(current, expectedRevision);
        ModelDefaults defaults = current.defaults();
        if (isDefaultOf(defaults, id)) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "连接「" + id + "」仍是使用面默认值，请先切换默认连接再删除");
        }
        List<ModelConnection> connections = new ArrayList<>(current.connections());
        boolean removed = connections.removeIf(connection -> connection.id().equals(id));
        if (!removed) {
            throw new ModelConfigException(
                    ModelConfigException.CONNECTION_UNAVAILABLE, "连接不存在：" + id);
        }
        log.info("[Model] 删除模型连接 {}（操作人 {}）", id, operator);
        return persist(
                new ModelCatalog(
                        ModelCatalog.CURRENT_VERSION,
                        current.revision() + 1,
                        defaults,
                        connections));
    }

    /** 设置三个使用面的默认连接；编码面被环境变量锁定时拒绝修改。 */
    public synchronized ModelCatalog saveDefaults(
            ModelDefaults defaults, long expectedRevision, String operator) {
        requireWritable();
        ModelCatalog current = reloadStrict();
        requireRevision(current, expectedRevision);
        ModelDefaults normalized =
                defaults == null
                        ? ModelDefaults.EMPTY
                        : new ModelDefaults(
                                defaults.control(), defaults.coding(), defaults.utility());
        if (!normalized.control().isBlank()) {
            requireEnabledConnection(current, normalized.control(), "控制面默认连接");
        }
        if (!normalized.utility().isBlank()) {
            requireEnabledConnection(current, normalized.utility(), "轻量面默认连接");
        }
        ModelConnection coding = null;
        if (!normalized.coding().isBlank()) {
            coding = requireEnabledConnection(current, normalized.coding(), "编码面默认连接");
        }
        if (envCodingLocked()) {
            if (!normalized.coding().equals(current.defaults().coding())) {
                throw new ModelConfigException(
                        ModelConfigException.ENV_LOCKED,
                        "编码面已被 " + DevModelSettings.ENV_MODEL + " 锁定，页面默认只读");
            }
            normalized =
                    new ModelDefaults(
                            normalized.control(), current.defaults().coding(), normalized.utility());
        } else if (coding != null) {
            if (coding.numCtx(0) < DevModelSettings.MIN_CONTEXT) {
                throw new ModelConfigException(
                        ModelConfigException.CAPABILITY_UNSUPPORTED,
                        "编码面连接上下文 " + coding.numCtx(0) + " 低于 "
                                + DevModelSettings.MIN_CONTEXT
                                + "，请先设置 contextWindow");
            }
            if (!coding.declares(ModelConnection.CAP_TOOLS)) {
                throw new ModelConfigException(
                        ModelConfigException.CAPABILITY_UNSUPPORTED,
                        "编码面连接必须支持 tools 能力");
            }
        }
        return persist(
                new ModelCatalog(
                        ModelCatalog.CURRENT_VERSION,
                        current.revision() + 1,
                        normalized,
                        current.connections()));
    }

    private ModelCatalog stored() {
        long now = System.currentTimeMillis();
        ModelCatalog snapshot = cache;
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

    /** 写前严格重读：读取或解析失败抛 503，禁止用 TTL 旧目录覆盖远端配置。 */
    private synchronized ModelCatalog reloadStrict() {
        if (!settings.enabled()) {
            return ModelCatalog.empty();
        }
        NacosConfigClient.FetchResult fetched = client.fetchResult(DATA_ID);
        switch (fetched.status()) {
            case OK -> {
                try {
                    ModelCatalog parsed =
                            json.readValue(fetched.entry().content(), ModelCatalog.class);
                    return normalize(parsed);
                } catch (Exception e) {
                    throw new PayrollUnavailableException(
                            "读取 " + DATA_ID + " 失败：" + e.getMessage(), e);
                }
            }
            case ABSENT -> {
                return ModelCatalog.empty();
            }
            default -> throw new PayrollUnavailableException(
                    "读取 " + DATA_ID + " 失败：" + fetched.message());
        }
    }

    private ModelCatalog load(ModelCatalog previous) {
        if (!settings.enabled()) {
            return ModelCatalog.empty();
        }
        Optional<String> body = client.fetch(DATA_ID);
        if (body.isEmpty()) {
            return previous == null ? ModelCatalog.empty() : previous;
        }
        try {
            ModelCatalog normalized = normalize(json.readValue(body.get(), ModelCatalog.class));
            log.info(
                    "[Model] 从 Nacos 加载 {} 个模型连接（revision {}）",
                    normalized.connections().size(),
                    normalized.revision());
            return normalized;
        } catch (Exception e) {
            if (previous != null) {
                log.warn("[Model] 解析 {} 失败，沿用上次连接配置：{}", DATA_ID, e.getMessage());
                return previous;
            }
            log.warn("[Model] 解析 {} 失败，仅使用环境变量连接：{}", DATA_ID, e.getMessage());
            return ModelCatalog.empty();
        }
    }

    private static ModelCatalog normalize(ModelCatalog parsed) {
        return new ModelCatalog(
                parsed.version() <= 0 ? ModelCatalog.CURRENT_VERSION : parsed.version(),
                Math.max(0L, parsed.revision()),
                parsed.defaults(),
                parsed.connections());
    }

    private ModelCatalog persist(ModelCatalog next) {
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(next);
            if (!client.publish(DATA_ID, content)) {
                throw new ModelConfigException(
                        ModelConfigException.INVALID_CONNECTION,
                        "写入 Nacos 配置 " + DATA_ID + " 失败，请检查 Nacos 连接与账号权限");
            }
        } catch (ModelConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "序列化模型连接配置失败：" + e.getMessage(),
                    e);
        }
        cache = next;
        fetchedAt = System.currentTimeMillis();
        log.info("[Model] 已保存模型连接配置（共 {} 个，revision {}）",
                next.connections().size(), next.revision());
        return next;
    }

    private void requireRevision(ModelCatalog current, long expected) {
        if (expected >= 0 && expected != current.revision()) {
            throw new ModelConfigException(
                    ModelConfigException.REVISION_CONFLICT,
                    "连接配置已被其它实例修改（当前 revision "
                            + current.revision()
                            + "，提交 "
                            + expected
                            + "），请刷新后重试");
        }
    }

    private static boolean isDefaultOf(ModelDefaults defaults, String id) {
        return id.equals(defaults.control())
                || id.equals(defaults.coding())
                || id.equals(defaults.utility());
    }

    private ModelConnection available(ModelCatalog catalog, String id, String label) {
        ModelConnection connection =
                catalog.connection(id)
                        .or(() -> findBuiltin(id))
                        .orElseThrow(
                                () ->
                                        new ModelConfigException(
                                                ModelConfigException.CONNECTION_UNAVAILABLE,
                                                label + " 不存在，请重新选择"));
        if (!connection.isEnabled()) {
            throw new ModelConfigException(
                    ModelConfigException.CONNECTION_UNAVAILABLE, label + " 已停用");
        }
        return connection;
    }

    private Optional<ModelConnection> findBuiltin(String id) {
        if (ModelConnections.ENV_CONTROL_ID.equals(id)) {
            return Optional.of(ModelConnections.envControl());
        }
        if (ModelConnections.ENV_CODING_ID.equals(id)) {
            return ModelConnections.envCoding();
        }
        return Optional.empty();
    }

    private ModelConnection requireEnabledConnection(
            ModelCatalog catalog, String id, String label) {
        ModelConnection connection =
                catalog.connection(id)
                        .or(() -> findBuiltin(id))
                        .orElseThrow(
                                () ->
                                        new ModelConfigException(
                                                ModelConfigException.CONNECTION_UNAVAILABLE,
                                                label + "「" + id + "」不存在"));
        if (!connection.isEnabled()) {
            throw new ModelConfigException(
                    ModelConfigException.CONNECTION_UNAVAILABLE, label + "「" + id + "」已停用");
        }
        return connection;
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION,
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），模型连接只读");
        }
    }

    private static boolean isEnvId(String id) {
        return ModelConnections.ENV_CONTROL_ID.equals(id)
                || ModelConnections.ENV_CODING_ID.equals(id);
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
