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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 无 Redis 时的进程内事件总线：仅保证单实例续流，用于测试与单机部署。 */
public final class InMemoryTurnEventBus implements TurnEventBus {

    private static final long MAX_EVENTS = 10_000L;

    private final Map<String, List<TurnEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    @Override
    public long append(String turnId, String event, Map<String, Object> data) {
        long seq =
                seqs.computeIfAbsent(turnId, ignored -> new AtomicLong())
                        .incrementAndGet();
        List<TurnEvent> list =
                events.computeIfAbsent(turnId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            list.add(
                    new TurnEvent(
                            seq,
                            event,
                            io.agentscope.examples.payroll.support.JsonSupport.toJson(data)));
            while (list.size() > MAX_EVENTS) {
                list.remove(0);
            }
        }
        return seq;
    }

    @Override
    public List<TurnEvent> read(String turnId, long afterSeq, int max) {
        List<TurnEvent> source = events.get(turnId);
        if (source == null) {
            return List.of();
        }
        List<TurnEvent> result = new ArrayList<>();
        for (TurnEvent event : source) {
            if (event.seq() > afterSeq) {
                result.add(event);
                if (result.size() >= max) {
                    break;
                }
            }
        }
        return result;
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
        List<TurnEvent> source = events.get(turnId);
        if (source == null) {
            return 0L;
        }
        synchronized (source) {
            return source.isEmpty() ? 0L : source.get(0).seq();
        }
    }

    @Override
    public TurnSnapshot snapshot(String turnId) {
        List<TurnEvent> source = events.get(turnId);
        if (source == null) {
            return null;
        }
        TurnSnapshotState state = new TurnSnapshotState();
        for (TurnEvent event : source) {
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
}
