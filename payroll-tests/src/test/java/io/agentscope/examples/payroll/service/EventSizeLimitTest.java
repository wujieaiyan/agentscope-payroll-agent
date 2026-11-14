package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventSizeLimitTest {

    private static final int LIMIT = 64 * 1024;

    @Test
    @DisplayName("小负载原样返回，不做额外拷贝")
    void keepsSmallPayload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", "你好");
        data.put("seq", 3);

        assertEquals(data, ChatSessionManager.limitEventSize("turn", "text", data));
    }

    @Test
    @DisplayName("超长文本按上限截断并打标")
    void truncatesOversizedText() {
        String text = "薪".repeat(40_000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("seq", 3);

        Map<String, Object> capped = ChatSessionManager.limitEventSize("turn", "text", data);

        String kept = (String) capped.get("text");
        assertTrue(kept.length() < text.length());
        assertEquals(Boolean.TRUE, capped.get("truncated"));
        assertEquals(3, capped.get("seq"));
        assertTrue(bytes(capped) <= LIMIT);
    }

    @Test
    @DisplayName("非文本大对象降级为标量字段并标记丢弃字节数")
    void dropsLargeCollections() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            items.add("item-" + i);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("message_id", 12L);

        Map<String, Object> capped = ChatSessionManager.limitEventSize("turn", "done", data);

        assertEquals(12L, capped.get("message_id"));
        assertEquals(Boolean.TRUE, capped.get("truncated"));
        assertTrue((Integer) capped.get("dropped_bytes") > LIMIT);
        assertTrue(bytes(capped) <= LIMIT);
    }

    private static int bytes(Map<String, Object> data) {
        return JsonSupport.toJson(data).getBytes(StandardCharsets.UTF_8).length;
    }
}
