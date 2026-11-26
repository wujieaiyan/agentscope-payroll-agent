package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
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
 *
 * <p>推送模式下每个订阅持有有界队列，写操作交给独立写池串行执行；队列溢出或写池拒绝时
 * 直接下发 gap 并关闭该订阅，读取循环与轮次执行均不被慢客户端阻塞。写线程卡在 socket 上
 * 由单线程 watchdog 观测：只标记并摘除订阅，不调用任何 emitter API。
 */
final class TurnEventFanout implements AutoCloseable {

    /** 单轮本地缓冲上限；超出后丢弃最旧事件，订阅者以落库消息收尾。 */
    private static final int BUFFER_LIMIT = 2048;

    /** 单次读取的最大事件数。 */
    private static final int READ_BATCH = 200;

    /** 单个订阅的待写队列上限默认值；实际值由 {@code payroll.chat.sse.subscriber-buffer} 覆盖。 */
    static final int SUBSCRIPTION_QUEUE_LIMIT = 512;

    /** 订阅者全部离开后的保留时长，便于短暂重连直接从本地缓冲补齐。 */
    private static final long DEFAULT_IDLE_MILLIS = 60_000L;

    private static final int DEFAULT_POLLER_THREADS = 4;

    /** 没有新事件时的轮询间隔；有事件时尽快再取一批，避免增加流式延迟。 */
    private static final long IDLE_POLL_MILLIS = 500L;
    private static final long ACTIVE_POLL_MILLIS = 10L;

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 节点内共享 poller 池；所有 fanout 实例共用，线程数受配置上限约束。 */
    private static volatile ScheduledExecutorService sharedPollers;
    private static volatile int sharedPollerThreads;

    /** 节点内共享写超时 watchdog：固定 1 线程，只读内存、只改状态。 */
    private static volatile ScheduledExecutorService sharedWatchdog;

    /** 参与指标汇总的实例：只有真正启用了推送订阅的实例才注册。 */
    private static final Set<TurnEventFanout> INSTANCES = ConcurrentHashMap.newKeySet();

    /** 写线程仍被阻塞的订阅；写线程返回后移出，gauge 反映「当前卡住的连接数」。 */
    private static final Set<Subscription> STALLED = ConcurrentHashMap.newKeySet();

    private final TurnEventBus bus;
    private final long idleMillis;
    private final int pollerThreads;
    private final ScheduledExecutorService pollers;
    private final SseFanoutSettings settings;
    private final Map<String, Hub> hubs = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean watchdogStarted;
    private volatile ScheduledFuture<?> watchdogFuture;

    TurnEventFanout(TurnEventBus bus) {
        this(bus, DEFAULT_IDLE_MILLIS, DEFAULT_POLLER_THREADS, null);
    }

    TurnEventFanout(TurnEventBus bus, Duration ignoredPollBlock, long idleMillis) {
        this(bus, idleMillis, DEFAULT_POLLER_THREADS, null);
    }

    TurnEventFanout(TurnEventBus bus, long idleMillis, int pollerThreads) {
        this(bus, idleMillis, pollerThreads, null);
    }

    TurnEventFanout(
            TurnEventBus bus, long idleMillis, int pollerThreads, SseFanoutSettings settings) {
        this.bus = bus;
        this.idleMillis = Math.max(0L, idleMillis);
        this.pollerThreads = Math.max(1, pollerThreads);
        this.pollers = sharedPollers(this.pollerThreads);
        this.settings = settings == null ? SseFanoutSettings.defaults() : settings;
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

    private static synchronized ScheduledExecutorService sharedWatchdog() {
        ScheduledExecutorService current = sharedWatchdog;
        if (current == null) {
            ScheduledThreadPoolExecutor pool =
                    new ScheduledThreadPoolExecutor(
                            1,
                            runnable -> {
                                Thread thread = new Thread(runnable, "sse-write-watchdog");
                                thread.setDaemon(true);
                                return thread;
                            });
            pool.setRemoveOnCancelPolicy(true);
            sharedWatchdog = pool;
            current = pool;
        }
        return current;
    }

    List<TurnEventBus.TurnEvent> await(
            String turnId, long afterSeq, Duration block, int max) {
        Hub hub = hubs.computeIfAbsent(turnId, id -> new Hub(id, Math.max(0L, afterSeq)));
        return hub.await(afterSeq, block, max);
    }

    /**
     * 注册一个推送订阅：先按契约补齐快照/缺口与存量事件，再交给 Hub 持续投递。
     *
     * <p>注册本身不阻塞；已注册的事件帧由写池串行写出。同一订阅同一时刻只有一个
     * drain 任务，保证 seq 严格递增。
     */
    ChatSseSubscription subscribe(
            String turnId, long afterSeq, long deadlineMillis, SseCallbacks callbacks) {
        ensureWatchdog();
        Hub hub = hubs.computeIfAbsent(turnId, id -> new Hub(id, Math.max(0L, afterSeq)));
        return hub.subscribe(afterSeq, deadlineMillis, callbacks);
    }

    private void ensureWatchdog() {
        if (watchdogStarted || closed) {
            return;
        }
        synchronized (this) {
            if (watchdogStarted || closed) {
                return;
            }
            INSTANCES.add(this);
            long tick = Math.max(100L, settings.watchdogTickMillis());
            watchdogFuture =
                    sharedWatchdog()
                            .scheduleWithFixedDelay(
                                    this::scanTick, tick, tick, TimeUnit.MILLISECONDS);
            watchdogStarted = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> watchdog = watchdogFuture;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        INSTANCES.remove(this);
        for (Hub hub : List.copyOf(hubs.values())) {
            hubs.remove(hub.turnId, hub);
            hub.close();
        }
        STALLED.removeIf(sub -> sub.hub.fanout == this);
    }

    /** watchdog 单次扫描：只读内存、只改状态；不发送 gap、不 complete、不访问 DB/Redis。 */
    private void scanTick() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Hub hub : List.copyOf(hubs.values())) {
            for (Subscription sub : hub.subscriptions) {
                sub.stallCheck(now, settings.writeTimeoutMillis());
            }
        }
        refreshGauges();
    }

    private static void refreshGauges() {
        int hubs = 0;
        int subscribers = 0;
        int inflight = 0;
        int writeQueue = 0;
        for (TurnEventFanout fanout : INSTANCES) {
            hubs += fanout.hubs.size();
            writeQueue += fanout.writerQueueSize();
            for (Hub hub : fanout.hubs.values()) {
                for (Subscription sub : hub.subscriptions) {
                    subscribers++;
                    if (sub.inFlight()) {
                        inflight++;
                    }
                }
            }
        }
        TurnMetrics.sseHubs(hubs);
        TurnMetrics.sseSubscribers(subscribers);
        TurnMetrics.sseWriteInflight(inflight);
        TurnMetrics.sseWriteQueue(writeQueue);
        TurnMetrics.sseStalled(STALLED.size());
        TurnMetrics.sseSlowClients(STALLED.size());
    }

    private int writerQueueSize() {
        Executor writer = settings.writer();
        if (writer instanceof ThreadPoolExecutor pool) {
            return pool.getQueue().size();
        }
        return 0;
    }

    /** 单轮读取循环：只有它访问事件总线，其余订阅者都从本地缓冲取数。 */
    private final class Hub {

        private final TurnEventFanout fanout = TurnEventFanout.this;
        private final String turnId;
        private final Deque<TurnEventBus.TurnEvent> buffer = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Subscription> subscriptions =
                new CopyOnWriteArrayList<>();
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
            long queuedAt = System.currentTimeMillis();
            try {
                pollers.schedule(
                        () -> pollOnce(this, queuedAt),
                        Math.max(0L, delayMillis),
                        TimeUnit.MILLISECONDS);
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

        private ChatSseSubscription subscribe(
                long afterSeq, long deadlineMillis, SseCallbacks callbacks) {
            long now = System.currentTimeMillis();
            Subscription sub;
            synchronized (this) {
                lastAccessAt = now;
                sub =
                        new Subscription(
                                this, callbacks, Math.max(0L, afterSeq), now, deadlineMillis);
                subscriptions.add(sub);
                if (closed) {
                    sub.close();
                    return sub;
                }
                replayLocked(sub, afterSeq);
            }
            sub.ensureDraining();
            return sub;
        }

        /**
         * 快照 → 缺口 → 存量事件，判定顺序固定；整体在 Hub 锁内完成，避免与 poller 交错。
         *
         * <p>重放超过订阅队列上限时下发缺口并跳到最新位置，保持 live 投递，不关闭订阅。
         */
        private void replayLocked(Subscription sub, long afterSeq) {
            long from = Math.max(0L, afterSeq);
            long earliest = safeEarliest();
            if (earliest > 0 && from + 1 < earliest) {
                TurnEventBus.TurnSnapshot snapshot = safeSnapshot();
                if (snapshot != null && snapshot.seq() > from) {
                    sub.offer(Frame.snapshot(snapshot));
                    from = snapshot.seq();
                }
                if (from + 1 < earliest) {
                    sub.offer(Frame.gap(from, earliest, false));
                    TurnMetrics.sseSubscriptionGap();
                    from = earliest - 1;
                }
            }
            long target = cursor;
            long position = from;
            boolean overflow = false;
            while (position < target) {
                List<TurnEventBus.TurnEvent> batch;
                try {
                    batch = bus.read(turnId, position, READ_BATCH);
                } catch (RuntimeException e) {
                    batch = List.of();
                }
                if (batch.isEmpty()) {
                    break;
                }
                for (TurnEventBus.TurnEvent event : batch) {
                    if (!sub.offer(Frame.event(event))) {
                        overflow = true;
                        break;
                    }
                    position = event.seq();
                    if (position >= target) {
                        break;
                    }
                }
                if (overflow || batch.size() < READ_BATCH) {
                    break;
                }
            }
            if (!overflow) {
                for (TurnEventBus.TurnEvent event : buffer) {
                    if (event.seq() <= sub.lastQueued()) {
                        continue;
                    }
                    if (!sub.offer(Frame.event(event))) {
                        overflow = true;
                        break;
                    }
                }
            }
            if (overflow) {
                long queued = sub.lastQueued();
                long next = Math.max(target, queued);
                sub.resetWithGap(sub.lastDelivered(), queued + 1, false);
                sub.advanceQueued(next);
                TurnMetrics.sseSubscriptionGap();
            }
        }

        private long safeEarliest() {
            try {
                return bus.earliestSeq(turnId);
            } catch (RuntimeException e) {
                return 0L;
            }
        }

        private TurnEventBus.TurnSnapshot safeSnapshot() {
            try {
                return bus.snapshot(turnId);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void remove(Subscription sub) {
            if (subscriptions.remove(sub) && subscriptions.isEmpty()) {
                lastAccessAt = System.currentTimeMillis();
            }
        }

        private void close() {
            closed = true;
            synchronized (this) {
                notifyAll();
            }
            for (Subscription sub : subscriptions) {
                sub.close();
            }
            subscriptions.clear();
        }

        private void expireIfIdle(long now) {
            if (subscriptions.isEmpty() && now - lastAccessAt > idleMillis) {
                closed = true;
                hubs.remove(turnId, this);
            }
        }
    }

    private void pollOnce(Hub hub, long queuedAt) {
        if (hub.closed) {
            return;
        }
        TurnMetrics.sseHubPollQueue(queuedAt);
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
        if (!hub.subscriptions.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Subscription sub : hub.subscriptions) {
                if (hub.closed) {
                    break;
                }
                sub.pump(hub);
                sub.tick(now, settings.pingMillis(), settings.idleCheckMillis());
            }
        }
        hub.schedulePoll(batch.isEmpty() ? IDLE_POLL_MILLIS : ACTIVE_POLL_MILLIS);
    }

    private static boolean isTerminal(TurnEventBus.TurnEvent event) {
        return "done".equals(event.event()) || "error".equals(event.event());
    }

    /** 订阅的一帧：事件、快照、缺口、保活、空闲检查或截止提示。 */
    private enum Kind {
        EVENT,
        SNAPSHOT,
        GAP,
        PING,
        IDLE,
        DEADLINE
    }

    private record Frame(
            Kind kind,
            TurnEventBus.TurnEvent event,
            TurnEventBus.TurnSnapshot snapshot,
            long afterSeq,
            long earliestSeq,
            boolean terminal) {

        static Frame event(TurnEventBus.TurnEvent event) {
            return new Frame(Kind.EVENT, event, null, 0L, 0L, isTerminal(event));
        }

        static Frame snapshot(TurnEventBus.TurnSnapshot snapshot) {
            return new Frame(Kind.SNAPSHOT, null, snapshot, 0L, 0L, false);
        }

        static Frame gap(long afterSeq, long earliestSeq, boolean terminal) {
            return new Frame(Kind.GAP, null, null, afterSeq, earliestSeq, terminal);
        }

        static Frame ping(long afterSeq) {
            return new Frame(Kind.PING, null, null, afterSeq, 0L, false);
        }

        static Frame idle() {
            return new Frame(Kind.IDLE, null, null, 0L, 0L, false);
        }

        static Frame deadline() {
            return new Frame(Kind.DEADLINE, null, null, 0L, 0L, true);
        }
    }

    /** 推送订阅：有界队列 + 单 drain + 写超时标记；关闭只影响自己，不阻塞轮次。 */
    private final class Subscription implements ChatSseSubscription {

        private final Hub hub;
        private final SseCallbacks callbacks;
        private final long deadlineMillis;
        private final Deque<Frame> queue = new ArrayDeque<>();
        private volatile long lastQueued;
        private volatile long lastDelivered;
        private volatile long inFlightSince;
        private volatile long lastWriteAt;
        private long lastPingAt;
        private long lastIdleAt;
        private boolean draining;
        private boolean closed;
        private boolean finished;

        private Subscription(
                Hub hub, SseCallbacks callbacks, long startSeq, long now, long deadlineMillis) {
            this.hub = hub;
            this.callbacks = callbacks;
            this.lastQueued = Math.max(0L, startSeq);
            this.lastDelivered = Math.max(0L, startSeq);
            this.deadlineMillis = deadlineMillis;
            this.lastWriteAt = now;
            this.lastPingAt = now;
            this.lastIdleAt = now;
        }

        private long lastQueued() {
            return lastQueued;
        }

        private long lastDelivered() {
            return lastDelivered;
        }

        private synchronized void advanceQueued(long seq) {
            lastQueued = Math.max(lastQueued, seq);
        }

        private synchronized boolean offer(Frame frame) {
            if (closed) {
                return false;
            }
            if (queue.size() >= settings.subscriberBuffer()) {
                return false;
            }
            queue.addLast(frame);
            if (frame.kind() == Kind.EVENT) {
                lastQueued = Math.max(lastQueued, frame.event().seq());
            }
            return true;
        }

        private synchronized void resetWithGap(long afterSeq, long earliestSeq, boolean terminal) {
            if (closed) {
                return;
            }
            queue.clear();
            queue.addLast(Frame.gap(afterSeq, earliestSeq, terminal));
        }

        private boolean inFlight() {
            return inFlightSince > 0;
        }

        /** poller 每轮把 Hub 缓冲里的新事件搬进本订阅队列；不做任何写操作。 */
        private void pump(Hub hub) {
            List<TurnEventBus.TurnEvent> pending = null;
            synchronized (hub) {
                long queued = lastQueued;
                for (TurnEventBus.TurnEvent event : hub.buffer) {
                    if (event.seq() <= queued) {
                        continue;
                    }
                    if (pending == null) {
                        pending = new ArrayList<>();
                    }
                    pending.add(event);
                }
            }
            if (pending == null) {
                return;
            }
            boolean overflow = false;
            for (TurnEventBus.TurnEvent event : pending) {
                if (!offer(Frame.event(event))) {
                    overflow = true;
                    break;
                }
            }
            if (overflow) {
                resetWithGap(lastDelivered, lastQueued + 1, true);
                TurnMetrics.sseSubscriptionGap();
            }
            ensureDraining();
        }

        /** poller 每轮检查保活、空闲检查与截止时间；帧同样经写池串行写出。 */
        private void tick(long now, long pingMillis, long idleMillis) {
            if (callbacks.closed().getAsBoolean()) {
                close();
                return;
            }
            Frame extra = null;
            boolean overflow = false;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (deadlineMillis > 0 && now >= deadlineMillis) {
                    extra = Frame.deadline();
                } else if (queue.isEmpty()) {
                    if (now - lastPingAt >= pingMillis) {
                        extra = Frame.ping(lastDelivered);
                        lastPingAt = now;
                    } else if (now - lastIdleAt >= idleMillis) {
                        extra = Frame.idle();
                        lastIdleAt = now;
                    }
                }
                if (extra != null) {
                    if (queue.size() >= settings.subscriberBuffer()) {
                        overflow = true;
                    } else {
                        queue.addLast(extra);
                    }
                }
            }
            if (overflow) {
                resetWithGap(lastDelivered, lastQueued + 1, true);
                TurnMetrics.sseSubscriptionGap();
            }
            ensureDraining();
        }

        /** 同一订阅同一时刻只允许一个 drain 任务；队列非空且无在途任务时提交一次。 */
        private void ensureDraining() {
            boolean schedule = false;
            synchronized (this) {
                if (!closed && !finished && !draining && !queue.isEmpty()) {
                    draining = true;
                    schedule = true;
                }
            }
            if (schedule) {
                submitDrain();
            }
        }

        private void submitDrain() {
            try {
                settings.writer().execute(this::drain);
            } catch (RejectedExecutionException e) {
                TurnMetrics.sseWriteRejected();
                boolean hadFrames;
                synchronized (this) {
                    hadFrames = !queue.isEmpty();
                    queue.clear();
                    draining = false;
                }
                if (hadFrames) {
                    TurnMetrics.sseSubscriptionGap();
                    safe(() -> callbacks.onGap().handle(lastDelivered, lastQueued + 1));
                }
                finish();
            }
        }

        /** 写线程：串行取出帧并调用回调；被卡在 socket 时由 watchdog 标记，不在此处阻塞他人。 */
        private void drain() {
            try {
                while (true) {
                    Frame frame;
                    synchronized (this) {
                        if (closed) {
                            queue.clear();
                            draining = false;
                            return;
                        }
                        frame = queue.pollFirst();
                        if (frame == null) {
                            draining = false;
                            return;
                        }
                        inFlightSince = System.currentTimeMillis();
                    }
                    boolean terminal;
                    try {
                        terminal = deliver(frame);
                    } catch (RuntimeException e) {
                        terminal = true;
                    } finally {
                        synchronized (this) {
                            inFlightSince = 0;
                            lastWriteAt = System.currentTimeMillis();
                        }
                    }
                    if (terminal) {
                        finish();
                        return;
                    }
                    if (callbacks.closed().getAsBoolean()) {
                        close();
                        return;
                    }
                }
            } finally {
                STALLED.remove(this);
            }
        }

        private boolean deliver(Frame frame) {
            switch (frame.kind()) {
                case EVENT -> {
                    callbacks.onEvent().accept(frame.event());
                    lastDelivered = Math.max(lastDelivered, frame.event().seq());
                    return frame.terminal();
                }
                case SNAPSHOT -> {
                    callbacks.onSnapshot().accept(frame.snapshot());
                    lastDelivered = Math.max(lastDelivered, frame.snapshot().seq());
                    return false;
                }
                case GAP -> {
                    callbacks.onGap().handle(frame.afterSeq(), frame.earliestSeq());
                    return frame.terminal();
                }
                case PING -> {
                    callbacks.onPing().accept(frame.afterSeq());
                    return false;
                }
                case IDLE -> {
                    callbacks.onIdle().run();
                    return false;
                }
                case DEADLINE -> {
                    callbacks.onDeadline().run();
                    return true;
                }
            }
            return true;
        }

        /**
         * watchdog 判定写超时：只标记、摘除并计数；不触发 emitter API。
         *
         * <p>写线程仍在 socket 上阻塞，容器写超时后由它自己返回并退出；此处的队列与
         * Hub 引用立即释放，后续事件不再投递。
         */
        private void stallCheck(long now, long timeoutMillis) {
            boolean stalled = false;
            synchronized (this) {
                if (closed || finished) {
                    return;
                }
                boolean pending = inFlightSince > 0 || !queue.isEmpty();
                long anchor = inFlightSince > 0 ? inFlightSince : lastWriteAt;
                if (pending && now - anchor > timeoutMillis) {
                    stalled = true;
                    closed = true;
                    finished = true;
                    queue.clear();
                    draining = false;
                }
            }
            if (stalled) {
                hub.remove(this);
                STALLED.add(this);
                TurnMetrics.sseWriteTimeout();
                safe(() -> callbacks.onStalled().run());
            }
        }

        private void finish() {
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                closed = true;
                queue.clear();
                draining = false;
            }
            hub.remove(this);
            STALLED.remove(this);
            safe(() -> callbacks.onTerminal().run());
        }

        @Override
        public void close() {
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                closed = true;
                queue.clear();
                draining = false;
            }
            hub.remove(this);
            STALLED.remove(this);
        }

        @Override
        public long lastSeq() {
            return lastDelivered;
        }

        @Override
        public boolean active() {
            synchronized (this) {
                return !closed && !finished;
            }
        }

        private void safe(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // 回调失败不能反向影响订阅状态与其他连接。
            }
        }
    }
}
