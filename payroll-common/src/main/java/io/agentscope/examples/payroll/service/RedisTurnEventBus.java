package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Stream 事件总线：节点崩溃后任意 Web 节点可按 turnId 续流。
 *
 * <p>事件序号由 INCR 生成并用显式 ID 写入 Stream；Redis 故障时退化为进程内事件，
 * 仅影响续流能力，不影响最终消息落库。
 */
public final class RedisTurnEventBus implements TurnEventBus {

    private static final System.Logger LOG = System.getLogger(RedisTurnEventBus.class.getName());
    private static final String EVENT_PREFIX = "payroll:chat:events:";
    private static final String SEQ_PREFIX = "payroll:chat:turnseq:";
    private static final String CANCEL_PREFIX = "payroll:chat:cancel:";
    private static final String SNAPSHOT_PREFIX = "payroll:chat:snapshot:";
    /**
     * 单次阻塞读取上限。必须小于 Redis 命令超时（默认 2s），否则 XREAD BLOCK 必然超时，
     * 而超时异常会被误判为 Redis 故障并触发永久降级。调用方通过循环等待达到更长的等待时间。
     */
    private static final Duration MAX_BLOCK = Duration.ofSeconds(1);

    /** 按条数裁剪每 N 条事件执行一次；事件 ID 以序号为首部，MINID 即序号水位。 */
    private static final long TRIM_EVERY = 32L;

    /** 每 N 条事件或每次终态事件持久化一次快照，刷新时先取快照再补增量。 */
    private static final long SNAPSHOT_EVERY = 16L;

    /** 连续失败达到阈值后熔断；冷却结束后放行一次半开探测。 */
    private static final int FAILURE_THRESHOLD = 3;

    private static final long OPEN_BASE_MILLIS = 10_000L;
    private static final long OPEN_MAX_MILLIS = 60_000L;
    /** 退避档位：10s → 20s → 40s → 60s（封顶），探测再失败保持 60s。 */
    private static final int OPEN_MAX_BACKOFF_LEVEL = 4;

    /**
     * 单次写入脚本：序号、XADD、TTL 与裁剪一次提交，把原先约 6 条命令压到 1 次往返。
     *
     * <p>事件 ID 为 {@code <seq>-0}（序号是续流游标，不能用时间戳当首部），
     * 条数维度用「当前序号 - 上限」作 MINID 水位并追加 MAXLEN 兜底；
     * 时长维度不按条数间隔刷新，而是在剩余 TTL 不足一半时重置，
     * 避免事件稀疏时「条数没到、键已过期」或「条数到了、时间远超」的脱节。
     */
    private static final RedisScript<Long> APPEND_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local seqKey = KEYS[1]
                    local eventKey = KEYS[2]
                    local seq = redis.call('INCR', seqKey)
                    if seq == 1 then
                      -- 序号键已重建：清掉可能残留的旧 ID 空间，避免 XADD 因 ID 回退失败
                      redis.call('DEL', eventKey)
                    end
                    redis.call('XADD', eventKey, seq .. '-0',
                      'event', ARGV[1], 'data', ARGV[2], 'v', ARGV[6], 'ts', ARGV[7])
                    local ttl = tonumber(ARGV[3])
                    local maxLen = tonumber(ARGV[4])
                    local trimEvery = tonumber(ARGV[5])
                    local pttl = redis.call('PTTL', eventKey)
                    if seq == 1 or pttl < 0 or pttl < (ttl * 500) then
                      redis.call('EXPIRE', seqKey, ttl)
                      redis.call('EXPIRE', eventKey, ttl)
                    end
                    if trimEvery > 0 and seq % trimEvery == 0 then
                      local watermark = seq - maxLen
                      if watermark > 0 then
                        redis.call('XTRIM', eventKey, 'MINID', watermark .. '-0')
                      end
                      redis.call('XTRIM', eventKey, 'MAXLEN', maxLen)
                    end
                    return seq
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration cancelTtl;
    private final long maxLen;
    private final InMemoryTurnEventBus fallback = new InMemoryTurnEventBus();
    private final Map<String, TurnSnapshotState> snapshots = new ConcurrentHashMap<>();
    private final CircuitBreaker breaker =
            new CircuitBreaker(FAILURE_THRESHOLD, OPEN_BASE_MILLIS, OPEN_MAX_MILLIS);

    public RedisTurnEventBus(StringRedisTemplate redis, int ttlHours, int maxEvents) {
        this(redis, ttlHours, maxEvents, 30);
    }

    /** cancelTtlMinutes 与轮次绝对上限对齐：取消标记只需覆盖本轮最长执行时间。 */
    public RedisTurnEventBus(
            StringRedisTemplate redis, int ttlHours, int maxEvents, int cancelTtlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
        this.cancelTtl = Duration.ofMinutes(Math.max(1, cancelTtlMinutes));
        this.maxLen = Math.max(100, maxEvents);
        warnOnEvictionPolicy();
    }

    public static RedisTurnEventBus local(int ttlHours, int maxEvents) {
        return new RedisTurnEventBus(null, ttlHours, maxEvents);
    }

    @Override
    public long append(String turnId, String event, Map<String, Object> data) {
        int mode = acquire();
        if (mode < 0) {
            return fallback.append(turnId, event, data);
        }
        try {
            Long seq =
                    redis.execute(
                            APPEND_SCRIPT,
                            List.of(SEQ_PREFIX + turnId, EVENT_PREFIX + turnId),
                            event == null ? "" : event,
                            JsonSupport.toJson(data),
                            Long.toString(ttl.toSeconds()),
                            Long.toString(maxLen),
                            Long.toString(TRIM_EVERY),
                            Integer.toString(VERSION),
                            Long.toString(System.currentTimeMillis()));
            if (seq == null) {
                throw new IllegalStateException("事件序号生成失败");
            }
            trackSnapshot(turnId, seq, event, data, mode);
            onSuccess(mode);
            return seq;
        } catch (RuntimeException e) {
            degrade("写入轮次事件失败", e, mode);
            return fallback.append(turnId, event, data);
        }
    }

    @Override
    public List<TurnEvent> read(String turnId, long afterSeq, int max) {
        int mode = acquire();
        if (mode < 0) {
            return fallback.read(turnId, afterSeq, max);
        }
        try {
            List<MapRecord<String, Object, Object>> records =
                    redis.opsForStream()
                            .range(
                                    EVENT_PREFIX + turnId,
                                    Range.<String>of(
                                            Range.Bound.inclusive(startId(afterSeq)),
                                            Range.Bound.unbounded()));
            List<TurnEvent> events = new ArrayList<>();
            for (MapRecord<String, Object, Object> record : records) {
                TurnEvent event = toEvent(record);
                if (event != null && event.seq() > afterSeq) {
                    events.add(event);
                    if (events.size() >= max) {
                        break;
                    }
                }
            }
            onSuccess(mode);
            return events;
        } catch (RuntimeException e) {
            degrade("读取轮次事件失败", e, mode);
            return fallback.read(turnId, afterSeq, max);
        }
    }

    @Override
    public List<TurnEvent> readBlocking(
            String turnId, long afterSeq, Duration block, int max) {
        int mode = acquire();
        if (mode < 0) {
            return fallback.readBlocking(turnId, afterSeq, block, max);
        }
        Duration effective = block;
        if (effective == null || effective.isNegative() || effective.isZero()) {
            effective = MAX_BLOCK;
        } else if (effective.compareTo(MAX_BLOCK) > 0) {
            effective = MAX_BLOCK;
        }
        try {
            List<MapRecord<String, Object, Object>> records =
                    redis.opsForStream()
                            .read(
                                    StreamReadOptions.empty().block(effective).count(max),
                                    StreamOffset.create(
                                            EVENT_PREFIX + turnId,
                                            ReadOffset.from(afterSeq + "-0")));
            List<TurnEvent> events = new ArrayList<>();
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    TurnEvent event = toEvent(record);
                    if (event != null && event.seq() > afterSeq) {
                        events.add(event);
                    }
                }
            }
            onSuccess(mode);
            return events;
        } catch (RuntimeException e) {
            if (isCommandTimeout(e)) {
                // 读取超时等价于"本轮次暂无新事件"，由调用方继续轮询，不降级。
                onSuccess(mode);
                return List.of();
            }
            degrade("阻塞读取轮次事件失败", e, mode);
            return fallback.readBlocking(turnId, afterSeq, block, max);
        }
    }

    @Override
    public long earliestSeq(String turnId) {
        int mode = acquire();
        if (mode < 0) {
            return fallback.earliestSeq(turnId);
        }
        try {
            List<MapRecord<String, Object, Object>> records =
                    redis.opsForStream()
                            .range(
                                    EVENT_PREFIX + turnId,
                                    Range.unbounded(),
                                    Limit.limit().count(1));
            if (records == null || records.isEmpty()) {
                onSuccess(mode);
                return 0L;
            }
            long seq = parseSeq(records.get(0).getId().getValue());
            onSuccess(mode);
            return seq;
        } catch (RuntimeException e) {
            degrade("读取最早轮次事件失败", e, mode);
            return fallback.earliestSeq(turnId);
        }
    }

    @Override
    public TurnSnapshot snapshot(String turnId) {
        int mode = acquire();
        if (mode < 0) {
            return fallback.snapshot(turnId);
        }
        String json;
        try {
            json = redis.opsForValue().get(SNAPSHOT_PREFIX + turnId);
            onSuccess(mode);
        } catch (RuntimeException e) {
            degrade("读取轮次快照失败", e, mode);
            return fallback.snapshot(turnId);
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonSupport.fromJson(json, TurnSnapshot.class);
        } catch (RuntimeException e) {
            // 快照损坏只影响首屏加速，按无快照处理，继续走事件重放。
            return null;
        }
    }

    private void trackSnapshot(
            String turnId, long seq, String event, Map<String, Object> data, int mode) {
        TurnSnapshotState state =
                snapshots.computeIfAbsent(turnId, ignored -> new TurnSnapshotState());
        state.apply(seq, event, data);
        boolean terminal = "done".equals(event) || "error".equals(event);
        if (seq % SNAPSHOT_EVERY != 0 && !terminal) {
            return;
        }
        try {
            redis.opsForValue()
                    .set(
                            SNAPSHOT_PREFIX + turnId,
                            JsonSupport.toJson(state.toSnapshot()),
                            ttl);
        } catch (RuntimeException e) {
            degrade("写入轮次快照失败", e, mode);
        }
        if (terminal) {
            snapshots.remove(turnId, state);
        }
    }

    @Override
    public void cancel(String turnId) {
        int mode = acquire();
        if (mode >= 0) {
            try {
                redis.opsForValue().set(CANCEL_PREFIX + turnId, "1", cancelTtl);
                onSuccess(mode);
                return;
            } catch (RuntimeException e) {
                degrade("写入取消标记失败", e, mode);
            }
        }
        fallback.cancel(turnId);
    }

    @Override
    public boolean cancelled(String turnId) {
        int mode = acquire();
        if (mode >= 0) {
            try {
                boolean cancelled = Boolean.TRUE.equals(redis.hasKey(CANCEL_PREFIX + turnId));
                onSuccess(mode);
                return cancelled;
            } catch (RuntimeException e) {
                degrade("读取取消标记失败", e, mode);
            }
        }
        return fallback.cancelled(turnId);
    }

    @Override
    public void clearCancel(String turnId) {
        fallback.clearCancel(turnId);
        int mode = acquire();
        if (mode >= 0) {
            try {
                redis.delete(CANCEL_PREFIX + turnId);
                onSuccess(mode);
            } catch (RuntimeException e) {
                degrade("清理取消标记失败", e, mode);
            }
        }
    }

    @Override
    public void clear(String turnId) {
        fallback.clear(turnId);
        snapshots.remove(turnId);
        int mode = acquire();
        if (mode >= 0) {
            try {
                redis.delete(EVENT_PREFIX + turnId);
                redis.delete(SEQ_PREFIX + turnId);
                redis.delete(CANCEL_PREFIX + turnId);
                redis.delete(SNAPSHOT_PREFIX + turnId);
                onSuccess(mode);
            } catch (RuntimeException e) {
                degrade("清理轮次事件失败", e, mode);
            }
        }
    }

    private static String startId(long afterSeq) {
        return afterSeq <= 0 ? "0" : "(" + afterSeq + "-0";
    }

    /**
     * 事件流依赖键不被淘汰：allkeys-* 策略在内存紧张时会删掉进行中的轮次事件，
     * 导致续流出现空洞。启动时探测一次策略并提示，探测失败不影响启动。
     */
    private void warnOnEvictionPolicy() {
        try {
            Object info =
                    redis.execute(
                            (RedisCallback<Object>)
                                    connection ->
                                            connection.execute(
                                                    "INFO",
                                                    "memory".getBytes(StandardCharsets.UTF_8)));
            String text =
                    info instanceof byte[] bytes
                            ? new String(bytes, StandardCharsets.UTF_8)
                            : String.valueOf(info);
            int index = text.indexOf("maxmemory_policy:");
            if (index < 0) {
                return;
            }
            int end = text.indexOf('\n', index);
            String policy =
                    text.substring(index + "maxmemory_policy:".length(), end < 0 ? text.length() : end)
                            .trim();
            if (policy.startsWith("allkeys")) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "[轮次] Redis maxmemory-policy="
                                + policy
                                + "，事件流键可能被提前淘汰；请为事件流使用独立实例/库并设置 noeviction 或 volatile-* 策略");
            }
        } catch (RuntimeException e) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "[轮次] 读取 Redis 淘汰策略失败，跳过检查：" + e.getMessage());
        }
    }

    private static TurnEvent toEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        if (value == null || !value.containsKey("event")) {
            return null;
        }
        Object event = value.get("event");
        Object data = value.get("data");
        long seq = parseSeq(record.getId().getValue());
        int version = parseInt(value.get("v"), VERSION);
        long timestamp = parseLong(value.get("ts"), 0L);
        TurnEvent parsed =
                new TurnEvent(
                        seq,
                        event == null ? "" : event.toString(),
                        data == null ? null : data.toString(),
                        version,
                        timestamp);
        if (!parsed.supported()) {
            // 更高版本的事件由更新的节点写入：跳过而不是整轮失败，前端仍以 done/落库为准。
            LOG.log(
                    System.Logger.Level.WARNING,
                    "[轮次] 跳过不兼容的事件：seq=" + seq + "，v=" + version + "，当前 v=" + VERSION);
            return null;
        }
        return parsed;
    }

    private static int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isCommandTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.lettuce.core.RedisCommandTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static long parseSeq(String id) {
        if (id == null) {
            return 0L;
        }
        int dash = id.indexOf('-');
        String value = dash > 0 ? id.substring(0, dash) : id;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 熔断判定：-1 表示熔断中直接走进程内事件；0 表示正常放行；1 表示冷却结束后的半开探测。
     *
     * <p>冷却期内只放行一个探测请求，避免故障未恢复时被并发流量打满。
     */
    private int acquire() {
        if (redis == null) {
            return -1;
        }
        int mode = breaker.acquire();
        if (mode < 0) {
            // 熔断期间的每次操作都实际走了进程内事件，计入降级量便于告警。
            TurnMetrics.degraded();
        }
        return mode;
    }

    private void onSuccess(int mode) {
        if (breaker.success(mode)) {
            LOG.log(System.Logger.Level.INFO, "[轮次] Redis 事件总线已恢复，重新启用跨节点续流");
        }
    }

    private void degrade(String action, RuntimeException error, int mode) {
        long cooldown = breaker.failure(mode);
        if (cooldown <= 0) {
            return;
        }
        LOG.log(
                System.Logger.Level.WARNING,
                "[轮次] "
                        + action
                        + "，事件总线熔断 "
                        + cooldown
                        + "ms，本段时间内降级为进程内事件："
                        + error.getMessage());
    }

    /**
     * 连续失败达阈值后打开；冷却结束放行一次半开探测，探测成功立即恢复、失败则加倍退避。
     */
    static final class CircuitBreaker {

        private final int failureThreshold;
        private final long baseMillis;
        private final long maxMillis;
        private final LongSupplier clock;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicBoolean probing = new AtomicBoolean();
        private volatile long openUntil;
        private volatile int streak;

        CircuitBreaker(int failureThreshold, long baseMillis, long maxMillis) {
            this(failureThreshold, baseMillis, maxMillis, System::currentTimeMillis);
        }

        CircuitBreaker(
                int failureThreshold,
                long baseMillis,
                long maxMillis,
                LongSupplier clock) {
            this.failureThreshold = Math.max(1, failureThreshold);
            this.baseMillis = Math.max(1L, baseMillis);
            this.maxMillis = Math.max(this.baseMillis, maxMillis);
            this.clock = clock;
        }

        int acquire() {
            long until = openUntil;
            if (until == 0L) {
                return 0;
            }
            if (clock.getAsLong() < until) {
                return -1;
            }
            return probing.compareAndSet(false, true) ? 1 : -1;
        }

        /** 返回 true 表示半开探测成功、熔断已关闭。 */
        boolean success(int mode) {
            if (mode == 1 && probing.compareAndSet(true, false)) {
                openUntil = 0L;
                streak = 0;
                failures.set(0);
                return true;
            }
            failures.set(0);
            return false;
        }

        /** 返回本次触发熔断的冷却时长；0 表示未触发。 */
        long failure(int mode) {
            boolean probe = mode == 1 && probing.compareAndSet(true, false);
            if (probe) {
                streak = Math.min(streak + 1, OPEN_MAX_BACKOFF_LEVEL);
                return trip();
            }
            if (openUntil != 0L) {
                // 其他请求已触发熔断，不再重复计数与告警
                return 0L;
            }
            if (failures.incrementAndGet() < failureThreshold) {
                return 0L;
            }
            failures.set(0);
            streak = 1;
            return trip();
        }

        boolean isOpen() {
            long until = openUntil;
            return until != 0L && clock.getAsLong() < until;
        }

        private long trip() {
            long cooldown =
                    Math.min(maxMillis, baseMillis << Math.min(streak - 1, OPEN_MAX_BACKOFF_LEVEL - 1));
            openUntil = clock.getAsLong() + cooldown;
            return cooldown;
        }
    }
}
