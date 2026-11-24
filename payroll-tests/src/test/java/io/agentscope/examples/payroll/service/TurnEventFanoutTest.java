package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.service.TurnEventBus.TurnEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
            // 空闲期约 900ms，共享读取循环按 150ms 轮询约 6 次；
            // 若三个订阅者各自轮询，次数约为 3 倍。
            Thread.sleep(900L);
            bus.append("turn-1", "done", Map.of("reply", "ab"));

            for (Future<List<TurnEvent>> future : futures) {
                List<TurnEvent> events = future.get(10, TimeUnit.SECONDS);
                assertEquals(List.of(1L, 2L, 3L), events.stream().map(TurnEvent::seq).toList());
                assertEquals("done", events.get(2).event());
            }
            assertTrue(
                    bus.reads.get() <= 12,
                    "同一轮次应只保留一个读取循环，实际 read 次数=" + bus.reads.get());
            assertEquals(0, bus.blockingReads.get(), "读取循环不再阻塞占用订阅线程");
            assertTrue(
                    bus.maxBatch.get() >= 2, "启动时应批量读取已有事件，实际最大批次="
                            + bus.maxBatch.get());
        } finally {
            fanout.close();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("推送订阅重放存量与实时事件，done 后触发终态回调")
    void pushSubscriptionReplaysAndStreams() throws Exception {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();
        bus.append("turn-2", "text", Map.of("text", "a"));
        bus.append("turn-2", "text", Map.of("text", "b"));
        List<Long> seqs = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(1);
        TurnEventFanout fanout = new TurnEventFanout(bus, 1_000L, 1, settings(Runnable::run));
        try (ChatSseSubscription ignored =
                fanout.subscribe(
                        "turn-2",
                        0L,
                        System.currentTimeMillis() + 5_000L,
                        new SseCallbacks(
                                event -> seqs.add(event.seq()),
                                snapshot -> {},
                                (after, earliest) -> {},
                                finished::countDown,
                                () -> {},
                                () -> {},
                                () -> {},
                                after -> {},
                                () -> false))) {
            bus.append("turn-2", "done", Map.of("reply", "ab"));
            assertTrue(finished.await(5, TimeUnit.SECONDS), "done 后应触发终态回调");
            assertEquals(List.of(1L, 2L, 3L), seqs);
        } finally {
            fanout.close();
        }
    }

    @Test
    @DisplayName("重放超过订阅队列上限时下发缺口并跳到最新，实时事件继续")
    void pushReplayOverflowSendsGapAndKeepsLive() throws Exception {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();
        for (int i = 0; i < 600; i++) {
            bus.append("turn-3", "text", Map.of("text", "x"));
        }
        AtomicInteger events = new AtomicInteger();
        AtomicInteger gaps = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        TurnEventFanout fanout = new TurnEventFanout(bus, 2_000L, 1, settings(Runnable::run));
        try {
            // 先让 Hub 把 600 条读进本地缓冲，订阅才会走「存量重放」而不是 poller 投递。
            List<TurnEvent> primed =
                    fanout.await("turn-3", 550L, Duration.ofSeconds(5), 100);
            assertTrue(!primed.isEmpty(), "Hub 应已读取到 550 之后的事件");
            try (ChatSseSubscription ignored =
                    fanout.subscribe(
                            "turn-3",
                            0L,
                            System.currentTimeMillis() + 5_000L,
                            new SseCallbacks(
                                    event -> events.incrementAndGet(),
                                    snapshot -> {},
                                    (after, earliest) -> gaps.incrementAndGet(),
                                    finished::countDown,
                                    () -> {},
                                    () -> {},
                                    () -> {},
                                    after -> {},
                                    () -> false))) {
                assertEquals(1, gaps.get(), "重放溢出只下发一次缺口");
                assertEquals(0, events.get(), "溢出后队列已清空，跳过的事件不再逐条下发");
                bus.append("turn-3", "done", Map.of("reply", "x"));
                assertTrue(finished.await(5, TimeUnit.SECONDS), "跳过后实时追加的 done 仍应送达");
            }
        } finally {
            fanout.close();
        }
    }

    @Test
    @DisplayName("写池拒绝时直接下发缺口并关闭订阅，不重试")
    void pushWriteRejectionSendsGapAndCloses() throws Exception {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();
        bus.append("turn-4", "text", Map.of("text", "a"));
        AtomicInteger gaps = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        SseFanoutSettings rejected =
                new SseFanoutSettings(
                        task -> {
                            throw new RejectedExecutionException("full");
                        },
                        60_000L,
                        60_000L,
                        5_000L,
                        200L,
                        512);
        TurnEventFanout fanout = new TurnEventFanout(bus, 1_000L, 1, rejected);
        try {
            try (ChatSseSubscription ignored =
                    fanout.subscribe(
                            "turn-4",
                            0L,
                            System.currentTimeMillis() + 5_000L,
                            new SseCallbacks(
                                    event -> {},
                                    snapshot -> {},
                                    (after, earliest) -> gaps.incrementAndGet(),
                                    finished::countDown,
                                    () -> {},
                                    () -> {},
                                    () -> {},
                                    after -> {},
                                    () -> false))) {
                assertTrue(finished.await(2, TimeUnit.SECONDS), "写池拒绝后订阅应结束");
                assertEquals(1, gaps.get());
            }
        } finally {
            fanout.close();
        }
    }

    @Test
    @DisplayName("写线程卡住超过阈值时 watchdog 只回调 onStalled")
    void stalledWriterIsMarkedByWatchdog() throws Exception {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch stalled = new CountDownLatch(1);
        SseFanoutSettings tight =
                new SseFanoutSettings(writer, 60_000L, 60_000L, 250L, 100L, 512);
        TurnEventFanout fanout = new TurnEventFanout(bus, 1_000L, 1, tight);
        try (ChatSseSubscription ignored =
                fanout.subscribe(
                        "turn-5",
                        0L,
                        System.currentTimeMillis() + 10_000L,
                        new SseCallbacks(
                                event -> {
                                    try {
                                        Thread.sleep(800L);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                },
                                snapshot -> {},
                                (after, earliest) -> {},
                                () -> {},
                                () -> {},
                                () -> {},
                                stalled::countDown,
                                after -> {},
                                () -> false))) {
            bus.append("turn-5", "text", Map.of("text", "slow"));
            assertTrue(stalled.await(5, TimeUnit.SECONDS), "写超时应被 watchdog 标记");
        } finally {
            fanout.close();
            writer.shutdownNow();
        }
    }

    private static SseFanoutSettings settings(java.util.concurrent.Executor writer) {
        return new SseFanoutSettings(writer, 60_000L, 60_000L, 5_000L, 200L, 512);
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
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger blockingReads = new AtomicInteger();
        private final AtomicInteger maxBatch = new AtomicInteger();

        @Override
        public long append(String turnId, String event, Map<String, Object> data) {
            return delegate.append(turnId, event, data);
        }

        @Override
        public List<TurnEvent> read(String turnId, long afterSeq, int max) {
            reads.incrementAndGet();
            List<TurnEvent> batch = delegate.read(turnId, afterSeq, max);
            maxBatch.accumulateAndGet(batch.size(), Math::max);
            return batch;
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
