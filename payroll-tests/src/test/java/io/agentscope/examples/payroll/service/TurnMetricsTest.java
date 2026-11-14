package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        TurnMetrics.useRegistry(null);
    }

    @Test
    @DisplayName("缺口、降级与租约丢失分别累计")
    void countsLifecycleEvents() {
        TurnMetrics.gap();
        TurnMetrics.gap();
        TurnMetrics.degraded();
        TurnMetrics.leaseLost();

        assertEquals(2.0, registry.counter(TurnMetrics.GAP).count());
        assertEquals(1.0, registry.counter(TurnMetrics.DEGRADE).count());
        assertEquals(1.0, registry.counter(TurnMetrics.LEASE_LOST).count());
    }

    @Test
    @DisplayName("单事件字节数按分布采样")
    void recordsEventBytes() {
        TurnMetrics.eventBytes(0);
        TurnMetrics.eventBytes(128);
        TurnMetrics.eventBytes(512);

        assertEquals(2L, registry.summary(TurnMetrics.EVENT_BYTES).count());
        assertEquals(640.0, registry.summary(TurnMetrics.EVENT_BYTES).totalAmount());
    }

    @Test
    @DisplayName("重放事件数与续流耗时只在有数据时采样")
    void recordsReplayAndResume() {
        TurnMetrics.replayedEvents(0);
        TurnMetrics.replayedEvents(7);
        TurnMetrics.resumeReplay(System.currentTimeMillis());

        assertEquals(1L, registry.summary(TurnMetrics.REPLAY_EVENTS).count());
        assertEquals(7.0, registry.summary(TurnMetrics.REPLAY_EVENTS).totalAmount());
        assertEquals(1L, registry.timer(TurnMetrics.RESUME).count());
    }
}
