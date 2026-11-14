package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 增量合并：短窗口内的 text / thinking 合并为一条事件，非增量事件前强制冲刷。 */
class ChatSseDeltaTest {

    @Test
    void mergesTextDeltasAndFlushesBeforePhase() throws Exception {
        Recorder recorder = new Recorder();
        ChatStreamListener listener = listener(recorder);

        listener.onText("你");
        listener.onText("好");
        listener.onPhase("正在核算");

        assertEquals(List.of("text", "phase"), recorder.names());
        assertEquals("你好", recorder.events().get(0).payload().get("text"));
        assertEquals("正在核算", recorder.events().get(1).payload().get("label"));
    }

    @Test
    void flushesThinkingOnTimer() throws Exception {
        Recorder recorder = new Recorder();
        ChatStreamListener listener = listener(recorder);

        listener.onThinking("先想");
        listener.onThinking("一下");
        long deadline = System.currentTimeMillis() + 2000;
        while (recorder.texts().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }

        assertEquals(List.of("thinking"), recorder.names());
        assertEquals("先想一下", recorder.events().get(0).payload().get("text"));
    }

    @Test
    void keepsAgentDeltasSeparate() throws Exception {
        Recorder recorder = new Recorder();
        ChatStreamListener listener = listener(recorder);

        listener.onAgentOutput("calc", "甲");
        listener.onAgentOutput("policy", "乙");
        listener.onAgentOutput("calc", "丙");
        listener.onAgentEnd("calc");

        assertTrue(recorder.names().contains("agent_end"));
        assertTrue(recorder.texts().contains("甲丙"));
        assertTrue(recorder.texts().contains("乙"));
    }

    @Test
    void emitsCitationsBeforeDone() throws Exception {
        Recorder recorder = new Recorder();
        ChatStreamListener listener = listener(recorder);
        Citation citation =
                new Citation(
                        1,
                        "K-1",
                        7L,
                        "人事制度",
                        11L,
                        "员工手册.pdf",
                        101L,
                        5,
                        "第三章 > 休假",
                        0.8,
                        List.of("keyword"),
                        "员工年假按工龄计算。");

        listener.onCitations(List.of(citation));
        listener.onDone(
                new PayrollChat.Reply(
                        "回答",
                        List.of(),
                        "stub:test",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(citation)));

        assertEquals(List.of("citation", "done"), recorder.names());
        List<?> emitted = (List<?>) recorder.events().get(0).payload().get("citations");
        assertEquals(1, emitted.size());
        Map<?, ?> item = (Map<?, ?>) emitted.get(0);
        assertEquals("K-1", item.get("point_id"));
        assertEquals("员工手册.pdf", item.get("document_name"));
        List<?> doneCitations = (List<?>) recorder.events().get(1).payload().get("citations");
        assertEquals(1, doneCitations.size());
    }

    private static ChatStreamListener listener(Recorder recorder) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(
                        invocation -> {
                            SseEmitter.SseEventBuilder builder = invocation.getArgument(0);
                            String name = null;
                            String data = null;
                            for (SseEmitter.DataWithMediaType item : builder.build()) {
                                for (String line :
                                        String.valueOf(item.getData()).split("\n")) {
                                    if (line.startsWith("event:")) {
                                        name = line.substring(6).trim();
                                    } else if (line.startsWith("data:")) {
                                        String value = line.substring(5).trim();
                                        if (!value.isEmpty()) {
                                            data = value;
                                        }
                                    } else if (line.startsWith("{")) {
                                        data = line;
                                    }
                                }
                            }
                            recorder.add(name, data);
                            return null;
                        })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        return ChatSse.bridge(ChatSse.attach(emitter), reply -> 1L);
    }

    private record Captured(String name, Map<String, Object> payload) {
    }

    private static final class Recorder {

        private final List<Captured> captured =
                Collections.synchronizedList(new ArrayList<>());

        void add(String name, String data) {
            Map<String, Object> payload =
                    data == null
                            ? Map.of()
                            : JsonSupport.fromJson(
                                    data, new TypeReference<Map<String, Object>>() {
                                    });
            captured.add(new Captured(name, payload));
        }

        List<Captured> events() {
            synchronized (captured) {
                return new ArrayList<>(captured);
            }
        }

        List<String> names() {
            return events().stream().map(Captured::name).toList();
        }

        List<String> texts() {
            List<String> result = new ArrayList<>();
            for (Captured event : events()) {
                if (event.payload().get("text") != null) {
                    result.add(String.valueOf(event.payload().get("text")));
                }
            }
            return result;
        }
    }
}
