package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationContextTest {

    @Test
    @DisplayName("超过阈值后只压缩较早轮次，最近四轮保留原文")
    void compressesOnlyOlderTurns() {
        ConversationContext context = new ConversationContext("", 0);
        for (int i = 1; i <= 7; i++) {
            context.append("问题" + i, "回答" + i);
        }

        assertTrue(context.needsCompression());
        List<ConversationContext.Turn> batch = context.compressionBatch();
        assertEquals(3, batch.size());
        assertEquals("问题1", batch.get(0).question());

        context.applySummary("用户关心张伟 2026-10 的工资", batch.size());

        assertEquals(4, context.recentTurnCount());
        assertEquals(3, context.summarizedTurns());
        assertTrue(context.render().contains("- 问题4"));
        assertFalse(context.render().contains("- 问题1"));
        assertFalse(context.render().contains("回答4"));
    }

    @Test
    @DisplayName("恢复上下文时跳过已摘要轮次并配对用户与助手消息")
    void restoresFromMessagesAndSnapshot() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            messages.add(message(i * 2L, "USER", "问题" + i));
            messages.add(message(i * 2L + 1, "ASSISTANT", "回答" + i));
        }
        messages.add(message(99, "ERROR", "失败"));

        ConversationContext context =
                ConversationContext.from(new ChatContextSnapshot("早前摘要", 2), messages);

        assertEquals(2, context.summarizedTurns());
        assertEquals(3, context.recentTurnCount());
        String rendered = context.render();
        assertTrue(rendered.contains("早前摘要"));
        assertTrue(rendered.contains("- 问题3"));
        assertFalse(rendered.contains("- 问题1"));
        assertFalse(rendered.contains("失败"));
    }

    @Test
    @DisplayName("压缩失败降级时保留已有摘要并丢弃最早轮次")
    void discardOldestKeepsExistingSummary() {
        ConversationContext context = new ConversationContext("旧摘要", 2);
        for (int i = 1; i <= 6; i++) {
            context.append("问题" + i, "回答" + i);
        }

        context.discardOldest(3, "（早期 3 轮对话因压缩失败已省略）");

        assertEquals(5, context.summarizedTurns());
        assertEquals(3, context.recentTurnCount());
        assertTrue(context.summary().contains("旧摘要"));
        assertTrue(context.summary().contains("压缩失败"));
    }

    @Test
    @DisplayName("上下文注入明确禁止引用历史金额")
    void augmentMarksHistoryAsReferenceOnly() {
        ConversationContext context = new ConversationContext("", 0);
        context.append("算一下张伟 2026-10 的工资", "实发 12000.00 元");

        String input = context.augment("他上个月呢");

        assertTrue(input.contains("仅用于理解指代"));
        assertTrue(input.contains("禁止引用历史数值"));
        assertTrue(input.contains("【本轮问题】\n他上个月呢"));
    }

    @Test
    @DisplayName("摘要中的金额与比例在代码层被抹掉")
    void sanitizesAmountsFromSummary() {
        ConversationContext context = new ConversationContext("", 0);
        for (int i = 1; i <= 6; i++) {
            context.append("问题" + i, "回答" + i);
        }

        context.applySummary(
                "张伟 2026-10 的工资已核算，实发 27,661.39 元，社保基数 12,000.00，个税比例 3%",
                context.compressionBatch().size());

        String summary = context.summary();
        assertFalse(summary.contains("27,661.39"));
        assertFalse(summary.contains("实发 27"));
        assertFalse(summary.contains("12,000.00"));
        assertFalse(summary.contains("3%"));
        assertFalse(summary.contains("〔"));
        assertTrue(summary.contains("张伟 2026-10 的工资已核算"));
    }

    @Test
    @DisplayName("空上下文直接返回原问题")
    void emptyContextLeavesQuestionUntouched() {
        ConversationContext context = new ConversationContext("", 0);
        assertEquals("算一下张伟 2026-10 的工资", context.augment("算一下张伟 2026-10 的工资"));
        assertTrue(context.render().isEmpty());
        assertFalse(context.needsCompression());
    }

    private static ChatMessage message(long id, String role, String content) {
        return new ChatMessage(id, 1L, role, content, List.of(), "test", LocalDateTime.now());
    }
}
