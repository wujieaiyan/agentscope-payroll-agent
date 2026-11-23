package io.agentscope.examples.payroll.service;

/**
 * 方案 B 的分阶段开关与材料预算（docs/kb-retrieval-as-context-spec.md）。
 *
 * <p>B0（执行者不再被 need_kb 劫持）与 B1（预取注入）默认关闭；B2 的引用过滤与创作类提示词
 * 各自独立开关，任一关闭即回到改造前行为。构造时统一收敛非法配置，避免运行期重复判断。
 */
public record KbChatSettings(
        boolean executorEnabled,
        boolean contextEnabled,
        int maxResults,
        int snippetChars,
        int maxChars,
        double minScore,
        int minOverlapTerms,
        long timeoutMs,
        boolean citationFilterEnabled,
        boolean creativeNoKb) {

    /** 注入条目硬上限，与配置位分离，防止误配放大提示词。 */
    public static final int MAX_RESULTS_HARD_LIMIT = 5;

    public KbChatSettings {
        maxResults = Math.max(1, Math.min(MAX_RESULTS_HARD_LIMIT, maxResults));
        snippetChars = Math.max(20, snippetChars);
        maxChars = Math.max(120, maxChars);
        minScore = Math.max(0.0d, Math.min(1.0d, minScore));
        minOverlapTerms = Math.max(0, minOverlapTerms);
        timeoutMs = Math.max(100L, timeoutMs);
    }

    public static KbChatSettings defaults() {
        return new KbChatSettings(false, false, 3, 200, 900, 0.0d, 1, 2000L, false, false);
    }
}
