package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.service.TurnEventBus.TurnEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatSseChannelTest {

    @Test
    void stampsSchemaVersionAndTimestamp() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSse.Channel channel = ChatSse.attach(emitter);

        channel.send("text", ChatSse.payload("text", "hi"), 7L);

        String body = captureBody(emitter);
        assertTrue(body.contains("\"v\":" + TurnEventBus.VERSION));
        assertTrue(body.contains("\"ts\":"));
        assertTrue(body.contains("id:7"));
        assertTrue(body.contains("\"text\":\"hi\""));
    }

    @Test
    void replayKeepsOriginalTimestamp() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSse.Channel channel = ChatSse.attach(emitter);

        ChatSse.replay(
                channel,
                new TurnEvent(3L, "text", "{\"text\":\"x\"}", 1, 1_700_000_000_000L));

        String body = captureBody(emitter);
        assertTrue(body.contains("\"ts\":1700000000000"));
        assertTrue(body.contains("\"seq\":3"));
    }

    private static String captureBody(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        return captor.getValue().build().stream()
                .map(
                        item ->
                                item.getData() instanceof byte[] bytes
                                        ? new String(bytes, StandardCharsets.UTF_8)
                                        : String.valueOf(item.getData()))
                .collect(Collectors.joining());
    }

    @Test
    void completeOnlyOnce() {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSse.Channel channel = ChatSse.attach(emitter);

        channel.complete();
        channel.complete();

        verify(emitter, times(1)).complete();
    }

    @Test
    void skipCompleteAfterClosed() {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSse.Channel channel = ChatSse.attach(emitter);

        channel.close();
        channel.complete();

        verify(emitter, never()).complete();
    }
}
