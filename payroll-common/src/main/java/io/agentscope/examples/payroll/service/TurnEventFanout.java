package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点内轮次事件分发：同一轮次只保留一个读取循环，本地缓冲供多个 SSE 订阅者共享。
 *
 * <p>多个标签页或客户端同时尾随同一轮次时，Redis 读取次数与连接数解耦；缓冲被裁剪后
 * 落后的订阅者会看到序号跳变，由调用方按既有 {@code gap} 契约处理。
 */
final class TurnEventFanout implements AutoCloseable {

    /** 单轮本地缓冲上限；超出后丢弃最旧事件，订阅者以落库消息收尾。 */
    private static final int BUFFER_LIMIT = 2048;

    /** 订阅者全部离开后的保留时长，便于短暂重连直接从本地缓冲补齐。 */
    private static final long DEFAULT_IDLE_MILLIS = 60_000L;

    private static final Duration DEFAULT_POLL_BLOCK = Duration.ofSeconds(10);

    private final TurnEventBus bus;
    private final Duration pollBlock;
    private final long idleMillis;
    private final Map<String, Hub> hubs = new ConcurrentHashMap<>();

    TurnEventFanout(TurnEventBus bus) {
        this(bus, DEFAULT_POLL_BLOCK, DEFAULT_IDLE_MILLIS);
    }

    TurnEventFanout(TurnEventBus bus, Duration pollBlock, long idleMillis) {
        this.bus = bus;
        this.pollBlock = pollBlock;
        this.idleMillis = idleMillis;
    }

    List<TurnEventBus.TurnEvent> await(
            String turnId, long afterSeq, Duration block, int max) {
        Hub hub =
                hubs.compute(
                        turnId,
                        (id, existing) ->
                                existing == null ? new Hub(id, Math.max(0L, afterSeq)) : existing);
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
        private boolean closed;
        private Thread poller;

        private Hub(String turnId, long cursor) {
            this.turnId = turnId;
            this.cursor = cursor;
            poller = new Thread(this::pollLoop, "turn-events-" + shortId(turnId));
            poller.setDaemon(true);
            poller.start();
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

        private void pollLoop() {
            while (true) {
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastAccessAt > idleMillis) {
                        closed = true;
                        hubs.remove(turnId, this);
                        return;
                    }
                }
                List<TurnEventBus.TurnEvent> batch;
                try {
                    batch = bus.readBlocking(turnId, cursor, pollBlock, 200);
                } catch (RuntimeException e) {
                    batch = List.of();
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (batch.isEmpty()) {
                    continue;
                }
                synchronized (this) {
                    for (TurnEventBus.TurnEvent event : batch) {
                        if (event.seq() <= cursor) {
                            continue;
                        }
                        buffer.addLast(event);
                        cursor = event.seq();
                        terminal = isTerminal(event);
                    }
                    while (buffer.size() > BUFFER_LIMIT) {
                        buffer.removeFirst();
                    }
                    notifyAll();
                }
            }
        }

        private void close() {
            synchronized (this) {
                closed = true;
                notifyAll();
            }
            poller.interrupt();
        }

        private String turnId() {
            return turnId;
        }
    }

    private static boolean isTerminal(TurnEventBus.TurnEvent event) {
        return "done".equals(event.event()) || "error".equals(event.event());
    }

    private static String shortId(String turnId) {
        return turnId == null || turnId.length() <= 8 ? String.valueOf(turnId) : turnId.substring(0, 8);
    }
}
