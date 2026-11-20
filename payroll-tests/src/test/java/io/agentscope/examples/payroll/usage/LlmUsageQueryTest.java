package io.agentscope.examples.payroll.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmUsageQueryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    @Test
    @DisplayName("range 支持 24h/7d/30d，未知取值回落 24h")
    void rangeMapsWindows() {
        assertEquals(
                Duration.ofHours(24),
                Duration.between(
                        LlmUsageQuery.range("24h", NOW, null, null, null, null).from(),
                        LlmUsageQuery.range("24h", NOW, null, null, null, null).to()));
        assertEquals(
                Duration.ofDays(7),
                Duration.between(
                        LlmUsageQuery.range("7d", NOW, null, null, null, null).from(), NOW));
        assertEquals(
                Duration.ofDays(30),
                Duration.between(
                        LlmUsageQuery.range("30d", NOW, null, null, null, null).from(), NOW));
        assertEquals(
                Duration.ofHours(24),
                Duration.between(
                        LlmUsageQuery.range("bogus", NOW, null, null, null, null).from(), NOW));
        assertEquals(
                Duration.ofHours(24),
                Duration.between(
                        LlmUsageQuery.range(null, NOW, null, null, null, null).from(), NOW));
    }

    @Test
    @DisplayName("过滤条件去空白，空串按未过滤处理")
    void rangeNormalizesFilters() {
        LlmUsageQuery query =
                LlmUsageQuery.range("7d", NOW, " qwen2.5:3b ", "  ", "", "\t");

        assertEquals("qwen2.5:3b", query.model());
        assertNull(query.plane());
        assertNull(query.agent());
        assertNull(query.userId());
    }

    @Test
    @DisplayName("range 大小写不敏感")
    void rangeIsCaseInsensitive() {
        assertEquals(
                Duration.ofDays(7),
                Duration.between(
                        LlmUsageQuery.range(" 7D ", NOW, null, null, null, null).from(), NOW));
    }

    @Test
    @DisplayName("时间窗必须左闭右开，非法窗口直接拒绝")
    void rejectsInvalidWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmUsageQuery(NOW, NOW, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmUsageQuery(NOW, NOW.minusMinutes(1), null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmUsageQuery(null, NOW, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmUsageQuery(NOW, null, null, null, null, null));
    }

    @Test
    @DisplayName("lastHours 至少 1 小时，过滤条件同样归一化")
    void lastHoursClampsToOne() {
        LlmUsageQuery query = LlmUsageQuery.lastHours(0, NOW, " ", " 控制面 ", null, null);

        assertEquals(NOW.minusHours(1), query.from());
        assertEquals(NOW, query.to());
        assertNull(query.model());
        assertEquals("控制面", query.plane());
    }

    @Test
    @DisplayName("now 为空时以当前时间收口")
    void defaultsEndToNow() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        LlmUsageQuery query = LlmUsageQuery.range("24h", null, null, null, null, null);
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        assertTrue(query.to().isAfter(before) && query.to().isBefore(after));
        assertEquals(Duration.ofHours(24), Duration.between(query.from(), query.to()));
    }
}
