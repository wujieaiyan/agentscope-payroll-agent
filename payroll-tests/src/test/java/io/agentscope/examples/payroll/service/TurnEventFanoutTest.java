package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.service.TurnEventBus.TurnEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnEventFanoutTest {

    @Test
    @DisplayName("同一轮次多个订阅者共享一次读取循环，且都能拿到完整事件")
    void subscribersShareSingleReader() throws Exception {
        CountingBus bus = new CountingBus();
        TurnEventFanout fanout = new TurnEventFanout(bus, Duration.ofMillis(200), 5_000L);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            bus.append("turn-1", "text", Map.of("text", "a"));
            bus.append("turn-1", "text", Map.of("text", "b"));

            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<TurnEvent>>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> collect(fanout, "turn-1", start)));
            }
            start.countDown();
            // 订阅者等待期间只应由一个读取循环轮询；若各自轮询，次数约为订阅者的 3 倍。
            Thread.sleep(900L);
            bus.append("turn-1", "done", Map.of("reply", "ab"));

            for (Future<List<TurnEvent>> future : futures) {
                List<TurnEvent> events = future.get(10, TimeUnit.SECONDS);
                assertEquals(List.of(1L, 2L, 3L), events.stream().map(TurnEvent::seq).toList());
                assertEquals("done", events.get(2).event());
            }
            assertTrue(
                    bus.blockingReads.get() <= 10,
                    "同一轮次应只保留一个读取循环，实际 readBlocking 次数="
                            + bus.blockingReads.get());
        } finally {
            fanout.close();
            pool.shutdownNow();
        }
    }

    private static List<TurnEvent> collect(
            TurnEventFanout fanout, String turnId, CountDownLatch start) throws Exception {
        start.await();
        List<TurnEvent> collected = new ArrayList<>();
        long cursor = 0L;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            List<TurnEvent> batch = fanout.await(turnId, cursor, Duration.ofMillis(200), 200);
            if (batch.isEmpty()) {
                continue;
            }
            collected.addAll(batch);
            cursor = batch.get(batch.size() - 1).seq();
            if (batch.stream().anyMatch(event -> "done".equals(event.event()))) {
                return collected;
            }
        }
        return collected;
    }

    private static final class CountingBus implements TurnEventBus {

        private final InMemoryTurnEventBus delegate = new InMemoryTurnEventBus();
        private final AtomicInteger blockingReads = new AtomicInteger();

        @Override
        public long append(String turnId, String event, Map<String, Object> data) {
            return delegate.append(turnId, event, data);
        }

        @Override
        public List<TurnEvent> read(String turnId, long afterSeq, int max) {
            return delegate.read(turnId, afterSeq, max);
        }

        @Override
        public List<TurnEvent> readBlocking(
                String turnId, long afterSeq, Duration block, int max) {
            blockingReads.incrementAndGet();
            return delegate.readBlocking(turnId, afterSeq, block, max);
        }

        @Override
        public long earliestSeq(String turnId) {
            return delegate.earliestSeq(turnId);
        }

        @Override
        public void cancel(String turnId) {
            delegate.cancel(turnId);
        }

        @Override
        public boolean cancelled(String turnId) {
            return delegate.cancelled(turnId);
        }

        @Override
        public void clearCancel(String turnId) {
            delegate.clearCancel(turnId);
        }

        @Override
        public void clear(String turnId) {
            delegate.clear(turnId);
        }
    }
}
