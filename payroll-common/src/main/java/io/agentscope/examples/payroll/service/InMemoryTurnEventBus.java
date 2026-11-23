package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 无 Redis 时的进程内事件总线：仅保证单实例续流，用于测试与单机部署。 */
public final class InMemoryTurnEventBus implements TurnEventBus {

    /** 每个轮次保留的事件上限；写满后覆盖最旧事件。 */
    static final int MAX_EVENTS = 10_000;

    private final Map<String, EventRing> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    @Override
    public long append(String turnId, String event, Map<String, Object> data) {
        long seq =
                seqs.computeIfAbsent(turnId, ignored -> new AtomicLong())
                        .incrementAndGet();
        events.computeIfAbsent(turnId, ignored -> new EventRing())
                .add(
                        new TurnEvent(
                                seq,
                                event,
                                io.agentscope.examples.payroll.support.JsonSupport.toJson(data)));
        return seq;
    }

    @Override
    public List<TurnEvent> read(String turnId, long afterSeq, int max) {
        EventRing ring = events.get(turnId);
        return ring == null ? List.of() : ring.after(afterSeq, max);
    }

    @Override
    public List<TurnEvent> readBlocking(
            String turnId, long afterSeq, Duration block, int max) {
        long deadline = System.currentTimeMillis() + Math.max(0L, block.toMillis());
        while (true) {
            List<TurnEvent> eventsNow = read(turnId, afterSeq, max);
            if (!eventsNow.isEmpty() || System.currentTimeMillis() >= deadline) {
                return eventsNow;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }

    @Override
    public long earliestSeq(String turnId) {
        EventRing ring = events.get(turnId);
        return ring == null ? 0L : ring.earliestSeq();
    }

    @Override
    public TurnSnapshot snapshot(String turnId) {
        EventRing ring = events.get(turnId);
        if (ring == null) {
            return null;
        }
        TurnSnapshotState state = new TurnSnapshotState();
        for (TurnEvent event : ring.after(0L, Integer.MAX_VALUE)) {
            Map<String, Object> data;
            try {
                data =
                        JsonSupport.fromJson(
                                event.data(),
                                new TypeReference<LinkedHashMap<String, Object>>() {
                                });
            } catch (RuntimeException e) {
                data = Map.of();
            }
            state.apply(event.seq(), event.event(), data);
        }
        TurnSnapshot snapshot = state.toSnapshot();
        return snapshot.seq() > 0 ? snapshot : null;
    }

    @Override
    public void cancel(String turnId) {
        cancelled.add(turnId);
    }

    @Override
    public boolean cancelled(String turnId) {
        return cancelled.contains(turnId);
    }

    @Override
    public void clearCancel(String turnId) {
        cancelled.remove(turnId);
    }

    @Override
    public void clear(String turnId) {
        events.remove(turnId);
        seqs.remove(turnId);
        cancelled.remove(turnId);
    }

    /**
     * 固定上限的环形缓冲：写满后覆盖最旧事件，追加与裁剪都是 O(1)。
     *
     * <p>容量按需从 64 扩容到 {@link #MAX_EVENTS}，避免为每个空会话预分配大数组。
     */
    private static final class EventRing {

        private TurnEvent[] slots = new TurnEvent[64];
        private int head;
        private int size;

        synchronized void add(TurnEvent event) {
            if (size == slots.length) {
                if (slots.length < MAX_EVENTS) {
                    grow();
                } else {
                    slots[head] = event;
                    head = (head + 1) % slots.length;
                    return;
                }
            }
            slots[(head + size) % slots.length] = event;
            size++;
        }

        synchronized List<TurnEvent> after(long afterSeq, int max) {
            List<TurnEvent> result = new ArrayList<>(Math.min(size, Math.max(0, max)));
            for (int i = 0; i < size; i++) {
                TurnEvent event = slots[(head + i) % slots.length];
                if (event != null && event.seq() > afterSeq) {
                    result.add(event);
                    if (result.size() >= max) {
                        break;
                    }
                }
            }
            return result;
        }

        synchronized long earliestSeq() {
            return size == 0 ? 0L : slots[head].seq();
        }

        private void grow() {
            TurnEvent[] bigger = new TurnEvent[Math.min(MAX_EVENTS, slots.length * 2)];
            for (int i = 0; i < size; i++) {
                bigger[i] = slots[(head + i) % slots.length];
            }
            slots = bigger;
            head = 0;
        }
    }
}
