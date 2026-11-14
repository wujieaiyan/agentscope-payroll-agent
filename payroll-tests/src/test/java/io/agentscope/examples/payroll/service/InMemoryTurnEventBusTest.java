package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.service.TurnEventBus.TurnEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryTurnEventBusTest {

    @Test
    void stampsSchemaVersionAndTimestamp() {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();

        bus.append("turn-1", "text", Map.of("text", "a"));

        TurnEvent event = bus.read("turn-1", 0L, 10).get(0);
        assertEquals(TurnEventBus.VERSION, event.version());
        assertTrue(event.timestamp() > 0L);
        assertTrue(event.supported());
    }

    @Test
    void reportsEarliestReadableSeq() {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();

        assertEquals(0L, bus.earliestSeq("turn-1"));
        bus.append("turn-1", "text", Map.of("text", "a"));
        bus.append("turn-1", "text", Map.of("text", "b"));

        assertEquals(1L, bus.earliestSeq("turn-1"));

        bus.clear("turn-1");
        assertEquals(0L, bus.earliestSeq("turn-1"));
    }

    @Test
    void clearsCancelMarkerWithoutDroppingEvents() {
        InMemoryTurnEventBus bus = new InMemoryTurnEventBus();
        bus.append("turn-1", "text", Map.of("text", "a"));
        bus.cancel("turn-1");
        assertTrue(bus.cancelled("turn-1"));

        bus.clearCancel("turn-1");

        assertEquals(false, bus.cancelled("turn-1"));
        assertEquals(1L, bus.earliestSeq("turn-1"));
    }
}
