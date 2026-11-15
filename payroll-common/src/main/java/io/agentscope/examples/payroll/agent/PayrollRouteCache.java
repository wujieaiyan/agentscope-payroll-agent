package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 薪酬路由决策缓存（规格 7.3）：Redis 为唯一介质，多实例共享；键含完整签名，
 * 签名任一变化自然失效并计入 {@code payroll.router.cache.invalid{reason}}。
 *
 * <p>写入门槛：同一桶连续两次 LLM 结果完全一致才落缓存；缓存不可用时全部 fail-open，
 * 不回退本地缓存，也不阻断对话。
 */
public final class PayrollRouteCache {

    public static final String KEY_PREFIX = "payroll:router:cache:";
    public static final String PENDING_PREFIX = KEY_PREFIX + "pending:";
    public static final String META_PREFIX = KEY_PREFIX + "meta:";
    public static final String INVALID_METRIC = "payroll.router.cache.invalid";

    static final Duration DEFAULT_TTL = Duration.ofDays(7);
    static final Duration PENDING_TTL = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(PayrollRouteCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Counter> INVALID_COUNTERS = new ConcurrentHashMap<>();

    /** 最小 KV 抽象：便于单测替换，生产固定为 Redis。 */
    public interface Store {
        String get(String key);

        void set(String key, String value, Duration ttl);
    }

    private static final Store EMPTY_STORE =
            new Store() {
                @Override
                public String get(String key) {
                    return null;
                }

                @Override
                public void set(String key, String value, Duration ttl) {
                    // 无缓存介质：全部按未命中处理
                }
            };

    private final Store store;
    private final Duration ttl;

    public PayrollRouteCache(Store store) {
        this(store, DEFAULT_TTL);
    }

    public PayrollRouteCache(Store store, Duration ttl) {
        this.store = store == null ? EMPTY_STORE : store;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }

    public static PayrollRouteCache disabled() {
        return new PayrollRouteCache(null);
    }

    public static PayrollRouteCache redis(StringRedisTemplate redis, Duration ttl) {
        if (redis == null) {
            return disabled();
        }
        return new PayrollRouteCache(
                new Store() {
                    @Override
                    public String get(String key) {
                        return redis.opsForValue().get(key);
                    }

                    @Override
                    public void set(String key, String value, Duration duration) {
                        redis.opsForValue().set(key, value, duration);
                    }
                },
                ttl);
    }

    /**
     * 决策键（规格 7.3）：桶 + 目录签名 + 流程绑定 + 角色绑定 + 团队可用 + 模型 id。
     */
    public record DecisionKey(
            String bucket,
            String catalogSignature,
            String flowBinding,
            String personaBinding,
            boolean teamAvailable,
            String modelId) {

        public DecisionKey {
            bucket = bucket == null ? "" : bucket.trim();
            catalogSignature = catalogSignature == null ? "" : catalogSignature.trim();
            flowBinding = flowBinding == null ? "" : flowBinding.trim();
            personaBinding = personaBinding == null ? "" : personaBinding.trim();
            modelId = modelId == null ? "" : modelId.trim();
        }

        public String tuple() {
            return catalogSignature
                    + "|"
                    + flowBinding
                    + "|"
                    + personaBinding
                    + "|"
                    + teamAvailable
                    + "|"
                    + modelId;
        }

        public String cacheKey() {
            return KEY_PREFIX + bucket + ":" + sha256(tuple());
        }

        String pendingKey() {
            return PENDING_PREFIX + bucket + ":" + sha256(tuple());
        }
    }

    /** 缓存值：原样复用决策写入时的 target/agent/reason/confidence。 */
    public record Entry(String target, String agentId, String reason, double confidence) {

        public Entry {
            target = target == null ? "" : target.trim();
            agentId = agentId == null ? "" : agentId.trim();
            reason = reason == null ? "" : reason;
            confidence = Math.max(0D, Math.min(1D, confidence));
        }
    }

    public Optional<Entry> lookup(DecisionKey key) {
        if (key == null || !PayrollRouteBucket.cacheable(key.bucket())) {
            return Optional.empty();
        }
        try {
            String raw = store.get(key.cacheKey());
            if (raw == null || raw.isBlank()) {
                noteMiss(key);
                return Optional.empty();
            }
            Entry entry = decode(raw);
            return entry == null ? Optional.empty() : Optional.of(entry);
        } catch (RuntimeException e) {
            log.debug("[薪酬语义] 读取决策缓存失败，回退模型路由：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** shadow 档只读缓存做比对，不记录失效元数据、不写任何键。 */
    public Optional<Entry> peek(DecisionKey key) {
        if (key == null || !PayrollRouteBucket.cacheable(key.bucket())) {
            return Optional.empty();
        }
        try {
            String raw = store.get(key.cacheKey());
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            Entry entry = decode(raw);
            return entry == null ? Optional.empty() : Optional.of(entry);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 记录一次 LLM 决策；与上次完全一致才写缓存，凑满"连续两次"门槛。
     *
     * @return 是否已写入缓存
     */
    public boolean record(DecisionKey key, Entry entry) {
        if (key == null
                || entry == null
                || !PayrollRouteBucket.cacheable(key.bucket())
                || entry.target().isBlank()) {
            return false;
        }
        try {
            String encoded = encode(entry);
            String previous = store.get(key.pendingKey());
            if (!encoded.equals(previous)) {
                store.set(key.pendingKey(), encoded, PENDING_TTL);
                return false;
            }
            store.set(key.cacheKey(), encoded, ttl);
            return true;
        } catch (RuntimeException e) {
            log.debug("[薪酬语义] 写入决策缓存失败，忽略：{}", e.getMessage());
            return false;
        }
    }

    /** 桶命中语义但缓存未命中时，比对签名并记录失效原因（catalog/flow/persona/team/model）。 */
    private void noteMiss(DecisionKey key) {
        String metaKey = META_PREFIX + key.bucket();
        try {
            String previous = store.get(metaKey);
            String current = key.tuple();
            if (previous != null && !previous.isBlank() && !previous.equals(current)) {
                invalid(reason(previous, current));
            }
            store.set(metaKey, current, ttl);
        } catch (RuntimeException ignored) {
            // 失效统计不能影响路由
        }
    }

    private static String reason(String previous, String current) {
        String[] left = previous.split("\\|", -1);
        String[] right = current.split("\\|", -1);
        String[] names = {"catalog", "flow", "persona", "team", "model"};
        int size = Math.min(names.length, Math.min(left.length, right.length));
        for (int i = 0; i < size; i++) {
            if (!left[i].equals(right[i])) {
                return names[i];
            }
        }
        return "catalog";
    }

    static void invalid(String reason) {
        Counter counter =
                INVALID_COUNTERS.computeIfAbsent(
                        reason,
                        value ->
                                Counter.builder(INVALID_METRIC)
                                        .description("薪酬路由缓存失效次数")
                                        .tag("reason", value)
                                        .register(Metrics.globalRegistry));
        counter.increment();
    }

    private static String encode(Entry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("target", entry.target());
        node.put("agent_id", entry.agentId());
        node.put("reason", entry.reason());
        node.put("confidence", entry.confidence());
        return node.toString();
    }

    private static Entry decode(String raw) {
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) {
                return null;
            }
            String target = node.path("target").asText("");
            if (target.isBlank()) {
                return null;
            }
            return new Entry(
                    target,
                    node.path("agent_id").asText(""),
                    node.path("reason").asText(""),
                    node.path("confidence").asDouble(0D));
        } catch (Exception e) {
            return null;
        }
    }

    /** 签名散列；同签名三元组在目录/绑定变化时必须不同。 */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
