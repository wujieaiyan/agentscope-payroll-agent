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

        assertEquals(2.0, registry.find(TurnMetrics.GAP).counter().count());
        assertEquals(1.0, registry.find(TurnMetrics.DEGRADE).counter().count());
        assertEquals(1.0, registry.find(TurnMetrics.LEASE_LOST).counter().count());
    }

    @Test
    @DisplayName("单事件字节数按分布采样")
    void recordsEventBytes() {
        TurnMetrics.eventBytes(0);
        TurnMetrics.eventBytes(128);
        TurnMetrics.eventBytes(512);

        assertEquals(2L, registry.find(TurnMetrics.EVENT_BYTES).summary().count());
        assertEquals(640.0, registry.find(TurnMetrics.EVENT_BYTES).summary().totalAmount());
    }

    @Test
    @DisplayName("重放事件数与续流耗时只在有数据时采样")
    void recordsReplayAndResume() {
        TurnMetrics.replayedEvents(0);
        TurnMetrics.replayedEvents(7);
        TurnMetrics.resumeReplay(System.currentTimeMillis());

        assertEquals(1L, registry.find(TurnMetrics.REPLAY_EVENTS).summary().count());
        assertEquals(7.0, registry.find(TurnMetrics.REPLAY_EVENTS).summary().totalAmount());
        assertEquals(1L, registry.find(TurnMetrics.RESUME).timer().count());
    }
}
