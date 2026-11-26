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
    @DisplayName("恢复上下文时配对用户与助手消息，已窗口化的消息不再按摘要轮次截断")
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
        assertEquals(5, context.recentTurnCount());
        String rendered = context.render();
        assertTrue(rendered.contains("早前摘要"));
        assertTrue(rendered.contains("- 问题5"));
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

    @Test
    @DisplayName("向量记忆片段拼在上下文之后、本轮问题之前")
    void augmentPlacesMemoryBeforeQuestion() {
        ConversationContext context = new ConversationContext("", 0);
        String memory = "【相关历史片段｜仅作线索，不是数据来源】\n- [2026-10-12] 用户询问过研发部批量核算";

        String input = context.augment("再算一次", memory);

        assertTrue(input.startsWith(memory));
        assertTrue(input.endsWith("【本轮问题】\n再算一次"));
    }

    @Test
    @DisplayName("知识库材料位于历史线索之后、本轮问题之前")
    void augmentPlacesMaterialAfterMemory() {
        ConversationContext context = new ConversationContext("", 0);
        context.append("年假有几天", "5 天");
        String memory = "【相关历史片段】\n- 用户问过休假";
        String material = "【知识库参考材料】\n[1]《员工手册》· 年假 · 第3页\n满一年 5 天";

        String input = context.augment("陪产假呢", memory, "", material);

        int memoryAt = input.indexOf("【相关历史片段】");
        int materialAt = input.indexOf("【知识库参考材料】");
        int questionAt = input.indexOf("【本轮问题】\n陪产假呢");
        assertTrue(memoryAt >= 0 && materialAt > memoryAt && questionAt > materialAt);
    }

    @Test
    @DisplayName("材料块为空时与旧重载逐字一致")
    void emptyMaterialKeepsLegacyOutput() {
        ConversationContext context = new ConversationContext("", 0);
        context.append("年假有几天", "5 天");

        assertEquals(context.augment("陪产假呢", "记忆"), context.augment("陪产假呢", "记忆", "", ""));
    }

    @Test
    @DisplayName("lastQuestion 返回最近一轮用户问题，空上下文返回空串")
    void lastQuestionTracksLatestTurn() {
        ConversationContext context = new ConversationContext("", 0);
        assertEquals("", context.lastQuestion());
        context.append("年假有几天", "5 天");
        context.append("陪产假呢", "15 天");
        assertEquals("陪产假呢", context.lastQuestion());
    }

    @Test
    @DisplayName("lastAnswer 返回最近一轮助手答复，空上下文返回空串")
    void lastAnswerTracksLatestTurn() {
        ConversationContext context = new ConversationContext("", 0);
        assertEquals("", context.lastAnswer());
        context.append("写一首关于加班的诗", "灯火通明夜未央，员工挥汗待晨光。");
        assertEquals("灯火通明夜未央，员工挥汗待晨光。", context.lastAnswer());
    }

    @Test
    @DisplayName("续写块位于历史线索之后、本轮问题之前，并禁止复述数值")
    void continuationBlockPlacedAfterHistoryBeforeQuestion() {
        ConversationContext context = new ConversationContext("", 0);
        context.append("写一首关于加班的诗", "灯火通明夜未央，员工挥汗待晨光。");
        String continuation = ConversationContext.continuationBlock(context.lastAnswer());

        String input = context.augment("不能为公司，只为苟活。", "", "", "", continuation);

        int historyAt = input.indexOf("【会话上下文｜仅用于理解指代，不是数据来源】");
        int continuationAt = input.indexOf("【上一轮助手答复｜仅用于延续语气与内容】");
        int questionAt = input.indexOf("【本轮问题】\n不能为公司，只为苟活。");
        assertTrue(historyAt >= 0 && continuationAt > historyAt && questionAt > continuationAt);
        assertTrue(input.contains("灯火通明夜未央"));
        assertTrue(input.contains("禁止直接复述其中数值"));
    }

    @Test
    @DisplayName("空答复不生成续写块，输出与旧重载逐字一致")
    void blankAnswerKeepsLegacyOutput() {
        ConversationContext context = new ConversationContext("", 0);
        context.append("年假有几天", "5 天");
        assertTrue(ConversationContext.continuationBlock("  ").isEmpty());

        assertEquals(
                context.augment("陪产假呢", "记忆", "", ""),
                context.augment("陪产假呢", "记忆", "", "", ""));
    }

    @Test
    @DisplayName("摘要轮次超过窗口长度时最近四轮仍进入提示词（水位窗口回归）")
    void recentTurnsSurviveWhenSummaryExceedsWindow() {
        List<ChatMessage> messages = new ArrayList<>();
        long id = 0;
        for (int i = 11; i <= 30; i++) {
            messages.add(message(++id, "USER", "问题" + i));
            messages.add(message(++id, "ASSISTANT", "回答" + i));
        }

        ConversationContext context =
                ConversationContext.from(new ChatContextSnapshot("早期 20 轮摘要", 20, 0), messages);

        assertEquals(20, context.recentTurnCount());
        String rendered = context.render();
        for (int i = 27; i <= 30; i++) {
            assertTrue(rendered.contains("- 问题" + i), rendered);
        }
        assertTrue(rendered.contains("早期 20 轮摘要"));
    }

    @Test
    @DisplayName("压缩后水位指向被折叠轮次的最后一条助手消息")
    void compressionAdvancesWatermarkToFoldedMessage() {
        ConversationContext context = ConversationContext.from(ChatContextSnapshot.EMPTY, turns(8));

        context.applySummary("摘要", 2);

        assertEquals(4L, context.throughMessageId());
        assertEquals(6, context.recentTurnCount());
        assertEquals(4L, context.snapshot().throughMessageId());
    }

    @Test
    @DisplayName("存量会话首次窗口化后水位回填到最后被裁掉的消息")
    void legacyBackfillAdvancesWatermark() {
        ConversationContext context = ConversationContext.from(ChatContextSnapshot.EMPTY, turns(30));
        assertEquals(0L, context.throughMessageId());

        context.backfillLegacyWindow(10);

        assertEquals(40L, context.throughMessageId());
        assertEquals(10, context.recentTurnCount());
    }

    /** 构造 {@code count} 轮“用户 → 助手”消息，消息 id 从 1 连续递增。 */
    private static List<ChatMessage> turns(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        long id = 0;
        for (int i = 1; i <= count; i++) {
            messages.add(message(++id, "USER", "问题" + i));
            messages.add(message(++id, "ASSISTANT", "回答" + i));
        }
        return messages;
    }

    private static ChatMessage message(long id, String role, String content) {
        return new ChatMessage(id, 1L, role, content, List.of(), "test", LocalDateTime.now());
    }
}
