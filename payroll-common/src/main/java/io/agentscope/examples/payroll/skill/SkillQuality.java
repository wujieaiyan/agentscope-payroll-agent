package io.agentscope.examples.payroll.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文与描述的只读质量评审：概览页要把「哪一条没通过、怎么改」直接展示出来，而保存路径
 * {@link SkillContentRules#requireBody(String)} 是抛异常、只回第一条。
 *
 * <p>本类不写库也不阻断任何操作，规则常量与校验片段来自 {@link SkillContentRules}，两条路径不漂移。
 */
public final class SkillQuality {

    private SkillQuality() {
    }

    /**
     * 一条评审结论。
     *
     * @param rule 规则标识（{@code description}/{@code required}/{@code length}/{@code heading}/{@code role}）
     * @param severity 目前与保存门禁一致，全部为 {@code error}；保留字段为后续「建议项」留位
     * @param message 与保存路径同一条文案，便于用户对照
     * @param hint 可操作的修改建议
     */
    public record Finding(String rule, String severity, String message, String hint) {

        public boolean error() {
            return "error".equals(severity);
        }
    }

    /** 逐条列出不通过项；全部通过时返回空列表。 */
    public static List<Finding> review(String description, String content) {
        List<Finding> findings = new ArrayList<>();
        SkillContentRules.Issue descriptionIssue = SkillContentRules.descriptionIssue(description);
        if (descriptionIssue != null) {
            findings.add(error(descriptionIssue));
        }
        SkillContentRules.bodyIssues(content).stream().map(SkillQuality::error).forEach(findings::add);
        return List.copyOf(findings);
    }

    /** 是否零 error；模板发布与概览 checklist 用它判断「正文通过质量检查」。 */
    public static boolean passes(String description, String content) {
        return review(description, content).stream().noneMatch(Finding::error);
    }

    private static Finding error(SkillContentRules.Issue issue) {
        return new Finding(issue.rule(), "error", issue.message(), issue.hint());
    }
}
