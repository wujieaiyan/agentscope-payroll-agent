package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

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
    /**
     * 单次阻塞读取上限。必须小于 Redis 命令超时（默认 2s），否则 XREAD BLOCK 必然超时，
     * 而超时异常会被误判为 Redis 故障并触发永久降级。调用方通过循环等待达到更长的等待时间。
     */
    private static final Duration MAX_BLOCK = Duration.ofSeconds(1);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final long maxLen;
    private final InMemoryTurnEventBus fallback = new InMemoryTurnEventBus();
    private volatile boolean degraded;

    public RedisTurnEventBus(StringRedisTemplate redis, int ttlHours, int maxEvents) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
        this.maxLen = Math.max(100, maxEvents);
    }

    public static RedisTurnEventBus local(int ttlHours, int maxEvents) {
        return new RedisTurnEventBus(null, ttlHours, maxEvents);
    }

    @Override
    public long append(String turnId, String event, Map<String, Object> data) {
        if (redis == null || degraded) {
            return fallback.append(turnId, event, data);
        }
        try {
            long seq = nextSeq(turnId);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", event);
            fields.put("data", JsonSupport.toJson(data));
            MapRecord<String, String, String> record =
                    StreamRecords.newRecord()
                            .in(EVENT_PREFIX + turnId)
                            .ofMap(fields)
                            .withId(RecordId.of(seq + "-0"));
            redis.opsForStream().add(record);
            redis.opsForStream().trim(EVENT_PREFIX + turnId, maxLen, true);
            redis.expire(EVENT_PREFIX + turnId, ttl);
            redis.expire(SEQ_PREFIX + turnId, ttl);
            return seq;
        } catch (RuntimeException e) {
            degrade("写入轮次事件失败", e);
            return fallback.append(turnId, event, data);
        }
    }

    @Override
    public List<TurnEvent> read(String turnId, long afterSeq, int max) {
        if (redis == null || degraded) {
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
            return events;
        } catch (RuntimeException e) {
            degrade("读取轮次事件失败", e);
            return fallback.read(turnId, afterSeq, max);
        }
    }

    @Override
    public List<TurnEvent> readBlocking(
            String turnId, long afterSeq, Duration block, int max) {
        if (redis == null || degraded) {
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
            return events;
        } catch (RuntimeException e) {
            if (isCommandTimeout(e)) {
                // 读取超时等价于"本轮次暂无新事件"，由调用方继续轮询，不降级。
                return List.of();
            }
            degrade("阻塞读取轮次事件失败", e);
            return fallback.readBlocking(turnId, afterSeq, block, max);
        }
    }

    @Override
    public void cancel(String turnId) {
        if (redis != null && !degraded) {
            try {
                redis.opsForValue().set(CANCEL_PREFIX + turnId, "1", Duration.ofHours(1));
                return;
            } catch (RuntimeException e) {
                degrade("写入取消标记失败", e);
            }
        }
        fallback.cancel(turnId);
    }

    @Override
    public boolean cancelled(String turnId) {
        if (redis != null && !degraded) {
            try {
                return Boolean.TRUE.equals(
                        redis.hasKey(CANCEL_PREFIX + turnId));
            } catch (RuntimeException e) {
                degrade("读取取消标记失败", e);
            }
        }
        return fallback.cancelled(turnId);
    }

    @Override
    public void clear(String turnId) {
        fallback.clear(turnId);
        if (redis != null && !degraded) {
            try {
                redis.delete(EVENT_PREFIX + turnId);
                redis.delete(SEQ_PREFIX + turnId);
                redis.delete(CANCEL_PREFIX + turnId);
            } catch (RuntimeException e) {
                degrade("清理轮次事件失败", e);
            }
        }
    }

    private long nextSeq(String turnId) {
        Long value = redis.opsForValue().increment(SEQ_PREFIX + turnId);
        if (value == null) {
            throw new IllegalStateException("事件序号生成失败");
        }
        redis.expire(SEQ_PREFIX + turnId, ttl);
        return value;
    }

    private static String startId(long afterSeq) {
        return afterSeq <= 0 ? "0" : "(" + afterSeq + "-0";
    }

    private static TurnEvent toEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        if (value == null || !value.containsKey("event")) {
            return null;
        }
        Object event = value.get("event");
        Object data = value.get("data");
        long seq = parseSeq(record.getId().getValue());
        return new TurnEvent(
                seq,
                event == null ? "" : event.toString(),
                data == null ? null : data.toString());
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

    private void degrade(String action, RuntimeException error) {
        if (!degraded) {
            degraded = true;
            LOG.log(
                    System.Logger.Level.WARNING,
                    "[轮次] " + action + "，已退化为进程内事件：" + error.getMessage());
        }
    }
}
