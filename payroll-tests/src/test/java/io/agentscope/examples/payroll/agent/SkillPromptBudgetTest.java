package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.skill.AgentSkill;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillPromptBudgetTest {

    private static final int WINDOW = 8192;

    private static final int PREDICT = 1024;

    @Test
    @DisplayName("预算按窗口扣除生成预留与 max(4096, 30%) 保留")
    void computesBudgetByReservation() {
        assertEquals(3072, SkillPromptBudget.budgetTokens(WINDOW, PREDICT));
        assertEquals(18842, SkillPromptBudget.budgetTokens(32768, 4096));
        assertEquals(0, SkillPromptBudget.budgetTokens(4096, 1024));
        assertEquals(0, SkillPromptBudget.budgetTokens(0, 0));
    }

    @Test
    @DisplayName("token 估算：CJK 一字一 token，ASCII 四字符一 token")
    void estimatesTokens() {
        assertEquals(0, SkillPromptBudget.estimateTokens(null));
        assertEquals(4, SkillPromptBudget.estimateTokens("工资核算"));
        assertEquals(1, SkillPromptBudget.estimateTokens("abcd"));
        assertEquals(1, SkillPromptBudget.estimateTokens("abc"));
        assertEquals(3, SkillPromptBudget.estimateTokens("工资abcd"));
    }

    @Test
    @DisplayName("关注文本命中者排到最前，正文优先占用预算")
    void ranksByFocusAndInjectsMostRelevantFirst() {
        AgentSkill alpha = skill("alpha-tools", "诗词生成与检索", huge());
        AgentSkill beta = skill("beta-tools", "无关的通用说明", huge());
        AgentSkill target = skill("tax-guide", "个税申报规则", huge());

        SkillPromptBudget.Result result =
                SkillPromptBudget.build(
                        List.of(alpha, beta, target), WINDOW, PREDICT, "个税申报");

        assertFalse(result.injectedIds().isEmpty(), result.block());
        assertEquals(target.getSkillId(), result.injectedIds().get(0));
        assertTrue(result.injectedIds().size() < 3, result.injectedIds().toString());
        assertTrue(result.catalogIds().size() >= 1, result.catalogIds().toString());
    }

    @Test
    @DisplayName("无关注文本或全部并列时保持绑定顺序")
    void keepsBindingOrderWithoutFocus() {
        AgentSkill first = skill("first-skill", "同一份说明", "正文一");
        AgentSkill second = skill("second-skill", "同一份说明", "正文二");

        assertEquals(
                List.of(first.getSkillId(), second.getSkillId()),
                SkillPromptBudget.rank(List.of(first, second), null).stream()
                        .map(AgentSkill::getSkillId)
                        .toList());
        assertEquals(
                List.of(first.getSkillId(), second.getSkillId()),
                SkillPromptBudget.rank(List.of(first, second), "zzz").stream()
                        .map(AgentSkill::getSkillId)
                        .toList());
        assertEquals(
                List.of(first.getSkillId(), second.getSkillId()),
                SkillPromptBudget.rank(List.of(first, second), "同一份说明").stream()
                        .map(AgentSkill::getSkillId)
                        .toList());
    }

    @Test
    @DisplayName("标签/元数据命中参与排序")
    void ranksByMetadata() {
        AgentSkill generic = skill("generic", "通用说明", "正文");
        AgentSkill tagged =
                AgentSkill.builder()
                        .name("tagged")
                        .description("通用说明")
                        .skillContent("正文")
                        .putMetadata("activation", List.of("发票核验"))
                        .build();

        List<AgentSkill> ranked =
                SkillPromptBudget.rank(List.of(generic, tagged), "发票核验");

        assertEquals(tagged.getSkillId(), ranked.get(0).getSkillId());
    }

    @Test
    @DisplayName("预算为零时全部退化为目录，不丢弃任何技能")
    void fallsBackToCatalogWhenBudgetExhausted() {
        List<AgentSkill> skills =
                IntStream.rangeClosed(1, 5)
                        .mapToObj(index -> skill("skill-" + index, "说明" + index, "正文" + index))
                        .toList();

        SkillPromptBudget.Result result =
                SkillPromptBudget.build(skills, WINDOW, 7000, null);

        assertEquals(0, result.budgetTokens());
        assertTrue(result.injectedIds().isEmpty(), result.injectedIds().toString());
        assertEquals(5, result.catalogIds().size(), result.catalogIds().toString());
        assertTrue(result.block().contains("其他可用技能"), result.block());
        assertTrue(
                result.block().contains("禁止编造"),
                "目录必须提示模型不要编造未注入技能的内容");
    }

    @Test
    @DisplayName("纯目录条数随窗口增长，超出部分写明剩余数量")
    void capsCatalogEntries() {
        List<AgentSkill> skills =
                IntStream.rangeClosed(1, 130)
                        .mapToObj(index -> contentlessSkill("empty-" + index, "说明" + index))
                        .toList();

        SkillPromptBudget.Result result =
                SkillPromptBudget.build(skills, WINDOW, PREDICT, null);

        assertTrue(result.injectedIds().isEmpty(), result.injectedIds().toString());
        assertEquals(130, result.catalogIds().size());
        assertTrue(result.block().contains("另有 2 个技能未列出"), result.block());
    }

    @Test
    @DisplayName("同名技能只注入一次，空正文进入目录")
    void dedupesAndCatalogsEmptyContent() {
        AgentSkill withContent = skill("payroll-calc", "工资核算", "正文");
        AgentSkill empty = contentlessSkill("auditor", "审计");

        SkillPromptBudget.Result result =
                SkillPromptBudget.build(
                        List.of(withContent, withContent, empty), WINDOW, PREDICT, null);

        assertEquals(1, result.injectedIds().size(), result.injectedIds().toString());
        assertEquals(List.of(empty.getSkillId()), result.catalogIds());
    }

    @Test
    @DisplayName("目录行只有 id 与描述，不渲染正文长度")
    void catalogLineOmitsContentLength() {
        // 长度是 strip() 归一后的字符数，Nacos 与库里同一份内容差 1 个字，
        // 渲染进提示词就让切库前后的目录集体改字节（规格 §17 V-12）。
        SkillPromptBudget.Result result =
                SkillPromptBudget.build(
                        List.of(contentlessSkill("tax-guide", "个税申报规则")),
                        WINDOW,
                        PREDICT,
                        null);

        assertTrue(
                result.block().contains("- tax-guide：个税申报规则\n"),
                result.block());
        assertFalse(result.block().contains("B）"), result.block());
    }

    @Test
    @DisplayName("关注文本分词：ASCII 过滤停用词，CJK 取二元组并限流")
    void extractsFocusTerms() {
        List<String> terms = SkillPromptBudget.focusTerms("Payroll the tax-guide 个税申报");

        assertTrue(terms.contains("payroll"), terms.toString());
        assertTrue(terms.contains("tax-guide"), terms.toString());
        assertTrue(terms.contains("个税"), terms.toString());
        assertTrue(terms.contains("申报"), terms.toString());
        assertFalse(terms.contains("the"), terms.toString());
        assertTrue(terms.size() <= 24, terms.toString());
        assertTrue(SkillPromptBudget.focusTerms("  ").isEmpty());
    }

    private static AgentSkill skill(String name, String description, String content) {
        return AgentSkill.builder()
                .name(name)
                .description(description)
                .skillContent(content)
                .source("test")
                .build();
    }

    /** AgentSkill 构造器拒绝空正文，目录退化场景用 mock 模拟正文为空的技能。 */
    private static AgentSkill contentlessSkill(String name, String description) {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.getSkillId()).thenReturn(name + ":test");
        when(skill.getName()).thenReturn(name);
        when(skill.getDescription()).thenReturn(description);
        when(skill.getSkillContent()).thenReturn("");
        when(skill.getMetadata()).thenReturn(Map.of());
        when(skill.getResources()).thenReturn(Map.of());
        when(skill.getSource()).thenReturn("test");
        return skill;
    }

    private static String huge() {
        return "核".repeat(100_000);
    }
}
