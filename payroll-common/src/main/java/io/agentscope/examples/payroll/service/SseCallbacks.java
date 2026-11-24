package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.service.TurnEventBus.TurnEvent;
import io.agentscope.examples.payroll.service.TurnEventBus.TurnSnapshot;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** 推送模式下每个 SSE 订阅的回调；全部在同一写线程内串行触发。 */
public record SseCallbacks(
        Consumer<TurnEvent> onEvent,
        Consumer<TurnSnapshot> onSnapshot,
        GapHandler onGap,
        Runnable onTerminal,
        Runnable onIdle,
        Runnable onDeadline,
        Runnable onStalled,
        LongConsumer onPing,
        BooleanSupplier closed) {

    public SseCallbacks {
        onEvent = onEvent == null ? event -> {} : onEvent;
        onSnapshot = onSnapshot == null ? snapshot -> {} : onSnapshot;
        onGap = onGap == null ? (afterSeq, earliestSeq) -> {} : onGap;
        onTerminal = onTerminal == null ? () -> {} : onTerminal;
        onIdle = onIdle == null ? () -> {} : onIdle;
        onDeadline = onDeadline == null ? () -> {} : onDeadline;
        onStalled = onStalled == null ? () -> {} : onStalled;
        onPing = onPing == null ? afterSeq -> {} : onPing;
        closed = closed == null ? () -> false : closed;
    }

    @FunctionalInterface
    public interface GapHandler {
        void handle(long afterSeq, long earliestSeq);
    }
}
