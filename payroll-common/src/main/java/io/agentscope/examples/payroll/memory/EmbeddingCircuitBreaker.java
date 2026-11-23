package io.agentscope.examples.payroll.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 嵌入服务熔断器：失败窗口内累计到阈值后打开，到期放行一次半开探测。
 *
 * <p>时钟可注入，便于测试三态迁移；状态读写都在同类锁内完成。
 */
public final class EmbeddingCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCircuitBreaker.class);

    private final int failures;
    private final long windowMillis;
    private final long openMillis;
    private final LongSupplier clock;
    private final Deque<Long> failureTimes = new ArrayDeque<>();

    private State state = State.CLOSED;
    private long openedAt;
    private boolean probeInFlight;

    public EmbeddingCircuitBreaker(int failures, long windowSeconds, long openSeconds) {
        this(failures, windowSeconds, openSeconds, System::currentTimeMillis);
    }

    EmbeddingCircuitBreaker(
            int failures, long windowSeconds, long openSeconds, LongSupplier clock) {
        this.failures = Math.max(1, failures);
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
        this.openMillis = Math.max(1L, openSeconds) * 1000L;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** 是否允许本次调用：CLOSED 放行，OPEN 到期转半开并只放行一次探测。 */
    public synchronized boolean allow() {
        long now = clock.getAsLong();
        if (state == State.OPEN) {
            if (now - openedAt < openMillis) {
                return false;
            }
            state = State.HALF_OPEN;
            probeInFlight = true;
            log.info("[向量] 嵌入熔断半开，放行一次探测");
            return true;
        }
        if (state == State.HALF_OPEN) {
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
            return true;
        }
        return true;
    }

    /** 半开探测未能执行（被拒绝或中断）：释放探测位，状态保持不变。 */
    public synchronized void abandonProbe() {
        probeInFlight = false;
    }

    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("[向量] 嵌入服务恢复，熔断关闭");
        }
        state = State.CLOSED;
        probeInFlight = false;
        failureTimes.clear();
    }

    public synchronized void recordFailure() {
        long now = clock.getAsLong();
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = now;
            probeInFlight = false;
            log.warn("[向量] 嵌入半开探测失败，重新熔断 {}s", openMillis / 1000L);
            return;
        }
        if (state == State.OPEN) {
            return;
        }
        failureTimes.addLast(now);
        while (!failureTimes.isEmpty() && now - failureTimes.peekFirst() > windowMillis) {
            failureTimes.removeFirst();
        }
        if (failureTimes.size() >= failures) {
            state = State.OPEN;
            openedAt = now;
            failureTimes.clear();
            log.warn("[向量] 嵌入连续失败 {} 次，熔断 {}s", failures, openMillis / 1000L);
        }
    }

    public synchronized State state() {
        return state;
    }

    /** OPEN 期间为 true；HALF_OPEN 允许探测，不算打开。 */
    public synchronized boolean isOpen() {
        return state == State.OPEN;
    }
}
