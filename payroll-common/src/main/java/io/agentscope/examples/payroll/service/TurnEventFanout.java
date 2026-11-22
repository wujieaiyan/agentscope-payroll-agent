package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点内轮次事件分发：同一轮次只保留一个读取循环，本地缓冲供多个 SSE 订阅者共享。
 *
 * <p>多个标签页或客户端同时尾随同一轮次时，Redis 读取次数与连接数解耦；缓冲被裁剪后
 * 落后的订阅者会看到序号跳变，由调用方按既有 {@code gap} 契约处理。
 *
 * <p>读取循环跑在节点共享的 poller 池上（默认 4 线程），不再为每个轮次创建裸线程；
 * 每次读取按批取事件，单轮 Redis 调用从每 token 一次降为每批一次。
 */
final class TurnEventFanout implements AutoCloseable {

    /** 单轮本地缓冲上限；超出后丢弃最旧事件，订阅者以落库消息收尾。 */
    private static final int BUFFER_LIMIT = 2048;

    /** 单次读取的最大事件数。 */
    private static final int READ_BATCH = 200;

    /** 订阅者全部离开后的保留时长，便于短暂重连直接从本地缓冲补齐。 */
    private static final long DEFAULT_IDLE_MILLIS = 60_000L;

    private static final int DEFAULT_POLLER_THREADS = 4;

    /** 没有新事件时的轮询间隔；有事件时尽快再取一批，避免增加流式延迟。 */
    private static final long IDLE_POLL_MILLIS = 150L;
    private static final long ACTIVE_POLL_MILLIS = 10L;

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 节点内共享 poller 池；所有 fanout 实例共用，线程数受配置上限约束。 */
    private static volatile ScheduledExecutorService sharedPollers;
    private static volatile int sharedPollerThreads;

    private final TurnEventBus bus;
    private final long idleMillis;
    private final int pollerThreads;
    private final ScheduledExecutorService pollers;
    private final Map<String, Hub> hubs = new ConcurrentHashMap<>();

    TurnEventFanout(TurnEventBus bus) {
        this(bus, DEFAULT_IDLE_MILLIS, DEFAULT_POLLER_THREADS);
    }

    TurnEventFanout(TurnEventBus bus, Duration ignoredPollBlock, long idleMillis) {
        this(bus, idleMillis, DEFAULT_POLLER_THREADS);
    }

    TurnEventFanout(TurnEventBus bus, long idleMillis, int pollerThreads) {
        this.bus = bus;
        this.idleMillis = idleMillis;
        this.pollerThreads = Math.max(1, pollerThreads);
        this.pollers = sharedPollers(this.pollerThreads);
    }

    private static synchronized ScheduledExecutorService sharedPollers(int threads) {
        ScheduledExecutorService current = sharedPollers;
        if (current == null || threads > sharedPollerThreads) {
            // 只扩容不缩容：先创建的实例线程数较小，后续按最大值补齐。
            ScheduledThreadPoolExecutor pool =
                    new ScheduledThreadPoolExecutor(
                            threads,
                            runnable -> {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "turn-events-poller-"
                                                        + POOL_SEQ.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });
            pool.setRemoveOnCancelPolicy(true);
            sharedPollers = pool;
            sharedPollerThreads = threads;
            if (current != null) {
                current.shutdown();
            }
            return pool;
        }
        return current;
    }

    List<TurnEventBus.TurnEvent> await(
            String turnId, long afterSeq, Duration block, int max) {
        Hub hub = hubs.computeIfAbsent(turnId, id -> new Hub(id, Math.max(0L, afterSeq)));
        return hub.await(afterSeq, block, max);
    }

    @Override
    public void close() {
        for (Hub hub : List.copyOf(hubs.values())) {
            hubs.remove(hub.turnId, hub);
            hub.close();
        }
    }

    /** 单轮读取循环：只有它访问事件总线，其余订阅者都从本地缓冲取数。 */
    private final class Hub {

        private final String turnId;
        private final Deque<TurnEventBus.TurnEvent> buffer = new ArrayDeque<>();
        private long cursor;
        private long lastAccessAt = System.currentTimeMillis();
        private boolean terminal;
        private volatile boolean closed;

        private Hub(String turnId, long cursor) {
            this.turnId = turnId;
            this.cursor = cursor;
            schedulePoll(0L);
        }

        private void schedulePoll(long delayMillis) {
            if (closed) {
                return;
            }
            try {
                pollers.schedule(
                        () -> pollOnce(this), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                closed = true;
            }
        }

        private synchronized List<TurnEventBus.TurnEvent> await(
                long afterSeq, Duration block, int max) {
            lastAccessAt = System.currentTimeMillis();
            long deadline = lastAccessAt + Math.max(0L, block.toMillis());
            while (true) {
                if (!buffer.isEmpty() && buffer.peekLast().seq() > afterSeq) {
                    break;
                }
                if (terminal || closed) {
                    break;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            lastAccessAt = System.currentTimeMillis();
            List<TurnEventBus.TurnEvent> result = new ArrayList<>();
            for (TurnEventBus.TurnEvent event : buffer) {
                if (event.seq() <= afterSeq) {
                    continue;
                }
                result.add(event);
                if (result.size() >= Math.max(1, max)) {
                    break;
                }
            }
            return result;
        }

        private String turnId() {
            return turnId;
        }

        private void close() {
            closed = true;
            synchronized (this) {
                notifyAll();
            }
        }

        private void expireIfIdle(long now) {
            if (now - lastAccessAt > idleMillis) {
                closed = true;
                hubs.remove(turnId, this);
            }
        }
    }

    private void pollOnce(Hub hub) {
        if (hub.closed) {
            return;
        }
        hub.expireIfIdle(System.currentTimeMillis());
        if (hub.closed) {
            return;
        }
        List<TurnEventBus.TurnEvent> batch;
        try {
            batch = bus.read(hub.turnId, hub.cursor, READ_BATCH);
        } catch (RuntimeException e) {
            batch = List.of();
        }
        if (!batch.isEmpty()) {
            synchronized (hub) {
                for (TurnEventBus.TurnEvent event : batch) {
                    if (event.seq() <= hub.cursor) {
                        continue;
                    }
                    hub.buffer.addLast(event);
                    hub.cursor = event.seq();
                    hub.terminal = isTerminal(event);
                }
                while (hub.buffer.size() > BUFFER_LIMIT) {
                    hub.buffer.removeFirst();
                }
                hub.notifyAll();
            }
        }
        hub.schedulePoll(batch.isEmpty() ? IDLE_POLL_MILLIS : ACTIVE_POLL_MILLIS);
    }

    private static boolean isTerminal(TurnEventBus.TurnEvent event) {
        return "done".equals(event.event()) || "error".equals(event.event());
    }
}
