package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevResultLimiterTest {

    @Test
    @DisplayName("读取超过 40000 字符时截断并提示 offset/limit")
    void truncatesRead() {
        String exact = "a".repeat(DevResultLimiter.MAX_READ_CHARS);
        assertEquals(exact, DevResultLimiter.truncateRead(exact));

        String oversized = "a".repeat(DevResultLimiter.MAX_READ_CHARS + 100);
        String truncated = DevResultLimiter.truncateRead(oversized);
        assertTrue(truncated.startsWith(exact));
        assertTrue(truncated.endsWith(DevResultLimiter.READ_MARKER));
        assertFalse(truncated.contains("a".repeat(DevResultLimiter.MAX_READ_CHARS + 1)));
        assertEquals(null, DevResultLimiter.truncateRead(null));
    }

    @Test
    @DisplayName("截断不会劈开代理对")
    void keepsSurrogatePair() {
        String content = "a".repeat(DevResultLimiter.MAX_READ_CHARS - 1) + "😀" + "b".repeat(10);
        String truncated = DevResultLimiter.truncateRead(content);
        char last = truncated.charAt(DevResultLimiter.MAX_READ_CHARS - 2);
        assertFalse(Character.isHighSurrogate(last));
        assertTrue(truncated.endsWith(DevResultLimiter.READ_MARKER));
    }

    @Test
    @DisplayName("单行超过 500 字符时附加行截断标记")
    void truncatesLine() {
        String shortLine = "x".repeat(DevResultLimiter.MAX_GREP_LINE_CHARS);
        assertEquals(shortLine, DevResultLimiter.truncateLine(shortLine));

        String longLine = "x".repeat(DevResultLimiter.MAX_GREP_LINE_CHARS + 20);
        assertTrue(DevResultLimiter.truncateLine(longLine).endsWith(DevResultLimiter.LINE_MARKER));
    }

    @Test
    @DisplayName("列表封顶保留前 N 条并给出省略数量")
    void limitsLists() {
        List<Integer> items = IntStream.range(0, 1205).boxed().toList();
        DevResultLimiter.Limited<Integer> limited = DevResultLimiter.limit(items, 1000);

        assertEquals(1000, limited.items().size());
        assertEquals(205, limited.omitted());
        assertTrue(DevResultLimiter.omittedNote(limited.omitted()).contains("205"));

        DevResultLimiter.Limited<Integer> small = DevResultLimiter.limit(List.of(1, 2), 1000);
        assertEquals(List.of(1, 2), small.items());
        assertEquals(0, small.omitted());
        assertEquals(0, DevResultLimiter.limit(null, 1000).omitted());
    }
}
