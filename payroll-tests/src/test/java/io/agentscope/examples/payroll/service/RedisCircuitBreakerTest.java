package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RedisCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final RedisTurnEventBus.CircuitBreaker breaker =
            new RedisTurnEventBus.CircuitBreaker(3, 10_000L, 60_000L, now::get);

    @Test
    void opensOnlyAfterThresholdFailures() {
        assertEquals(0, breaker.acquire());
        assertEquals(0L, breaker.failure(0));
        assertEquals(0L, breaker.failure(0));
        assertFalse(breaker.isOpen());

        assertEquals(10_000L, breaker.failure(0));
        assertTrue(breaker.isOpen());
        assertEquals(-1, breaker.acquire());
    }

    @Test
    void halfOpenProbeRestoresAfterCooldown() {
        trip();
        now.addAndGet(10_000L);

        assertEquals(1, breaker.acquire());
        assertEquals(-1, breaker.acquire(), "冷却期内只放行一个探测");
        assertTrue(breaker.success(1));

        assertFalse(breaker.isOpen());
        assertEquals(0, breaker.acquire());
    }

    @Test
    void failedProbeBacksOffExponentially() {
        trip();
        now.addAndGet(10_000L);

        assertEquals(1, breaker.acquire());
        assertEquals(20_000L, breaker.failure(1));
        assertEquals(-1, breaker.acquire());

        now.addAndGet(20_000L);
        assertEquals(1, breaker.acquire());
        assertEquals(40_000L, breaker.failure(1));

        now.addAndGet(40_000L);
        assertEquals(1, breaker.acquire());
        assertEquals(60_000L, breaker.failure(1), "退避封顶 60s");
    }

    private void trip() {
        breaker.failure(0);
        breaker.failure(0);
        breaker.failure(0);
        assertTrue(breaker.isOpen());
    }
}
