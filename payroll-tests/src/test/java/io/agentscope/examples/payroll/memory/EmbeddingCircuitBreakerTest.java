package io.agentscope.examples.payroll.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingCircuitBreakerTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    @Test
    @DisplayName("窗口内累计到阈值后打开，打开期间一律拒绝")
    void tripsOpenAfterThreshold() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);

        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(EmbeddingCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allow());

        breaker.recordFailure();

        assertEquals(EmbeddingCircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allow());
    }

    @Test
    @DisplayName("窗口外的旧失败不计入阈值")
    void oldFailuresExpireFromWindow() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);

        breaker.recordFailure();
        clock.addAndGet(30_000L);
        breaker.recordFailure();
        clock.addAndGet(31_000L);
        breaker.recordFailure();

        assertEquals(EmbeddingCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("打开到期后只放行一次半开探测，成功即关闭")
    void halfOpenAllowsSingleProbeThenCloses() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);
        open(breaker);

        assertFalse(breaker.allow(), "打开期间应拒绝");

        clock.addAndGet(60_000L);
        assertTrue(breaker.allow(), "到期后放行探测");
        assertEquals(EmbeddingCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.isOpen(), "半开不算打开");
        assertFalse(breaker.allow(), "探测在途时不再放行");

        breaker.recordSuccess();

        assertEquals(EmbeddingCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allow());
    }

    @Test
    @DisplayName("半开探测失败立即重新打开")
    void halfOpenFailureReopens() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);
        open(breaker);
        clock.addAndGet(60_000L);
        assertTrue(breaker.allow());

        breaker.recordFailure();

        assertEquals(EmbeddingCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allow());
    }

    @Test
    @DisplayName("探测未能执行时释放探测位，可再次尝试")
    void abandonProbeReleasesSlot() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);
        open(breaker);
        clock.addAndGet(60_000L);
        assertTrue(breaker.allow());
        assertFalse(breaker.allow());

        breaker.abandonProbe();

        assertEquals(EmbeddingCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.allow());
    }

    @Test
    @DisplayName("成功调用重置失败计数")
    void successResetsFailureWindow() {
        EmbeddingCircuitBreaker breaker = breaker(3, 60, 60);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(EmbeddingCircuitBreaker.State.CLOSED, breaker.state());
    }

    private EmbeddingCircuitBreaker breaker(int failures, long windowSeconds, long openSeconds) {
        return new EmbeddingCircuitBreaker(failures, windowSeconds, openSeconds, clock::get);
    }

    private void open(EmbeddingCircuitBreaker breaker) {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(EmbeddingCircuitBreaker.State.OPEN, breaker.state());
    }
}
