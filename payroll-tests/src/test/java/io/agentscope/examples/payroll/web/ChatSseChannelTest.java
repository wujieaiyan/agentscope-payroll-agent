package io.agentscope.examples.payroll.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatSseChannelTest {

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
