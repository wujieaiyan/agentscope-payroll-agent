package io.agentscope.examples.payroll.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能提示词预算（规格 4.3）：在模型上下文里贪心注入绑定技能正文，放不下的技能退化为
 * 目录行，绝不静默丢弃。
 *
 * <p>可用预算 = 上下文窗口 − 生成预留 − 安全保留，其中安全保留取
 * {@code max(4096, 窗口 × 30%)}。正文按本处关注文本（智能体名称/描述）的相关度排序后
 * 贪心注入，单技能最多 8000 字符；未注入的技能进入目录，目录条数受上限约束
 * （纯目录 ≥50 条且随窗口增长，混合最多 100 条）。相关度并列时保持绑定顺序，
 * 因此没有关键词命中时行为与旧版一致。
 */
public final class SkillPromptBudget {

    private static final Logger log = LoggerFactory.getLogger(SkillPromptBudget.class);

    static final int MAX_SKILL_CONTENT_CHARS = 8_000;

    private static final int MIN_CONTENT_CHARS = 512;

    private static final int MAX_CATALOG_MIXED = 100;

    private static final String TRUNCATED_NOTE = "\n（技能内容过长，已截断，请按上述规则执行）";

    private static final int MAX_FOCUS_TERMS = 24;

    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,}");

    private static final Set<String> STOP_WORDS =
            Set.of(
                    "the", "and", "for", "with", "from", "this", "that", "you", "are", "can",
                    "will", "not", "all", "use", "into", "when", "was", "has", "have");

    /**
     * @param block 可拼进系统提示词的片段
     * @param injectedIds 已注入正文的技能
     * @param catalogIds 仅列入目录的技能
     * @param budgetTokens 实际可用的技能预算
     */
    public record Result(
            String block,
            List<String> injectedIds,
            List<String> catalogIds,
            int budgetTokens) {

        public List<String> skippedIds() {
            return catalogIds;
        }
    }

    private SkillPromptBudget() {
    }

    public static int budgetTokens(int contextWindow, int numPredict) {
        int window = Math.max(0, contextWindow);
        int reserved = Math.max(4096, (int) Math.round(window * 0.3));
        return Math.max(0, window - Math.max(0, numPredict) - reserved);
    }

    /** 中文按 1 字符 1 token，其余按 4 字符 1 token 估算，偏保守。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) {
                wide++;
            }
        }
        int narrow = text.length() - wide;
        return wide + (narrow + 3) / 4;
    }

    public static Result build(List<AgentSkill> skills, int contextWindow, int numPredict) {
        return build(skills, contextWindow, numPredict, null);
    }

    /**
     * @param focus 本处关注文本（智能体名称/描述等）；非空时按关键词命中重排绑定技能，
     *     让最相关的正文优先占用预算、进入目录。空白或无命中时保持绑定顺序。
     */
    public static Result build(
            List<AgentSkill> skills, int contextWindow, int numPredict, String focus) {
        int budget = budgetTokens(contextWindow, numPredict);
        List<AgentSkill> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (skills != null) {
            for (AgentSkill skill : skills) {
                if (skill != null && seen.add(skill.getSkillId())) {
                    unique.add(skill);
                }
            }
        }
        unique = rank(unique, focus);
        String header = "## 已加载角色技能（必须遵循）\n";
        int remaining = budget - estimateTokens(header);
        StringBuilder injected = new StringBuilder();
        List<String> injectedIds = new ArrayList<>();
        List<AgentSkill> catalogSkills = new ArrayList<>();
        for (AgentSkill skill : unique) {
            String content = skill.getSkillContent() == null ? "" : skill.getSkillContent().strip();
            if (content.isEmpty()) {
                catalogSkills.add(skill);
                continue;
            }
            int titleTokens = estimateTokens("### " + skill.getSkillId());
            String body = fitContent(content, remaining - titleTokens);
            if (body == null) {
                catalogSkills.add(skill);
                continue;
            }
            injected.append("\n### ").append(skill.getSkillId()).append("\n").append(body).append("\n");
            remaining -= titleTokens + estimateTokens(body);
            injectedIds.add(skill.getSkillId());
        }

        String catalog = renderCatalog(catalogSkills, contextWindow, !injectedIds.isEmpty());
        StringBuilder block = new StringBuilder();
        if (!injectedIds.isEmpty()) {
            block.append(header).append(injected);
            if (catalog == null) {
                block.append('\n');
            }
        }
        if (catalog != null) {
            block.append(catalog).append('\n');
        }
        SkillMetrics.promptInjected(injectedIds.size());
        SkillMetrics.promptSkipped(unique.size() - injectedIds.size());
        if (catalogSkills.isEmpty()) {
            return new Result(block.toString(), List.copyOf(injectedIds), List.of(), budget);
        }
        List<String> catalogIds =
                catalogSkills.stream().map(AgentSkill::getSkillId).toList();
        List<String> notInjected =
                catalogIds.stream().filter(id -> !injectedIds.contains(id)).toList();
        if (!notInjected.isEmpty()) {
            log.info(
                    "[Skill] 未注入：{}（技能预算 {} tokens，已列入目录提示模型按需说明）",
                    notInjected,
                    budget);
        }
        return new Result(
                block.toString(), List.copyOf(injectedIds), List.copyOf(catalogIds), budget);
    }

    /**
     * 按关注文本做轻量关键词排序：命中数高者在前，并列时保持绑定顺序（规格 4.3）。
     * 只读元数据（id/name/description/metadata），不触碰正文，避免为排序计算哈希或扫描长文本。
     */
    static List<AgentSkill> rank(List<AgentSkill> skills, String focus) {
        List<String> terms = focusTerms(focus);
        if (skills.size() < 2 || terms.isEmpty()) {
            return skills;
        }
        record Ranked(AgentSkill skill, int index, int score) {}
        List<Ranked> ranked = new ArrayList<>(skills.size());
        for (int i = 0; i < skills.size(); i++) {
            AgentSkill skill = skills.get(i);
            ranked.add(new Ranked(skill, i, relevance(skill, terms)));
        }
        ranked.sort(
                Comparator.comparingInt(Ranked::score)
                        .reversed()
                        .thenComparingInt(Ranked::index));
        return ranked.stream().map(Ranked::skill).toList();
    }

    private static int relevance(AgentSkill skill, List<String> terms) {
        int score = hits(skill.getSkillId(), terms) * 3;
        score += hits(skill.getName(), terms) * 2;
        score += hits(skill.getDescription(), terms) * 2;
        score += hits(metadataText(skill.getMetadata()), terms) * 2;
        return score;
    }

    private static int hits(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                matched++;
            }
        }
        return matched;
    }

    /** 扁平化元数据（tags/activation 等）为可匹配文本，限制递归深度避免恶意嵌套。 */
    private static String metadataText(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        appendMetadata(out, metadata, 0);
        return out.toString();
    }

    private static void appendMetadata(StringBuilder out, Map<?, ?> map, int depth) {
        if (depth > 3) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.append(entry.getKey()).append(' ');
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                appendMetadata(out, nested, depth + 1);
            } else if (value instanceof Collection<?> values) {
                for (Object item : values) {
                    out.append(item).append(' ');
                }
            } else if (value != null) {
                out.append(value).append(' ');
            }
        }
    }

    /** 关注文本分词：ASCII 词（≥2 字符）与 CJK 二元组，大小写不敏感，最多保留 24 个。 */
    static List<String> focusTerms(String focus) {
        if (focus == null || focus.isBlank()) {
            return List.of();
        }
        String lower = focus.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher ascii = ASCII_TOKEN.matcher(lower);
        while (ascii.find() && terms.size() < MAX_FOCUS_TERMS) {
            String token = ascii.group();
            if (hasLetter(token) && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length() && terms.size() < MAX_FOCUS_TERMS; i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                cjk.append(c);
            } else if (cjk.length() > 0) {
                addGrams(terms, cjk);
                cjk.setLength(0);
            }
        }
        if (terms.size() < MAX_FOCUS_TERMS) {
            addGrams(terms, cjk);
        }
        return List.copyOf(terms);
    }

    private static void addGrams(Set<String> terms, StringBuilder run) {
        for (int i = 0; i + 1 < run.length() && terms.size() < MAX_FOCUS_TERMS; i++) {
            terms.add(run.substring(i, i + 2));
        }
    }

    private static boolean hasLetter(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** 按剩余 token 截断正文；连最小片段都放不下时返回 null，由目录承接。 */
    private static String fitContent(String content, int remainingTokens) {
        int charCap = Math.min(MAX_SKILL_CONTENT_CHARS, Math.max(0, remainingTokens * 3));
        if (charCap < Math.min(MIN_CONTENT_CHARS, content.length())) {
            return null;
        }
        String body = content.length() <= charCap ? content : content.substring(0, charCap) + TRUNCATED_NOTE;
        int tokens = estimateTokens(body);
        while (tokens > remainingTokens && charCap > MIN_CONTENT_CHARS) {
            charCap /= 2;
            if (charCap < MIN_CONTENT_CHARS) {
                return null;
            }
            body = content.length() <= charCap
                    ? content
                    : content.substring(0, charCap) + TRUNCATED_NOTE;
            tokens = estimateTokens(body);
        }
        return tokens > remainingTokens ? null : body;
    }

    private static String renderCatalog(List<AgentSkill> skills, int contextWindow, boolean mixed) {
        if (skills.isEmpty()) {
            return null;
        }
        int cap = mixed ? MAX_CATALOG_MIXED : Math.max(50, contextWindow / 64);
        StringBuilder catalog = new StringBuilder("## 其他可用技能（正文未注入）\n");
        int rendered = 0;
        for (AgentSkill skill : skills) {
            if (rendered >= cap) {
                break;
            }
            catalog.append(SkillManifests.fromSkill(skill).catalogLine()).append('\n');
            rendered++;
        }
        if (skills.size() > rendered) {
            catalog.append("- ……另有 ").append(skills.size() - rendered).append(" 个技能未列出\n");
        }
        catalog.append(
                "（这些技能的正文因上下文预算未注入；需要时先向用户说明，禁止编造其内容。）");
        return catalog.toString();
    }
}
