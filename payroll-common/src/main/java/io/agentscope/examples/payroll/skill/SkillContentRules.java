package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技能正文质量门槛：管理台保存与项目导入共用，避免两条入口规则漂移。
 *
 * <p>正文会直接注入系统提示词；过短或没有结构的正文会让模型自行补全角色设定。
 * 校验片段以 {@link Issue} 暴露给 {@link SkillQuality} 复用，保存路径仍用 {@link #requireBody(String)}
 * 在第一条不通过时抛异常——两条入口的判定口径必须是同一份代码。
 */
final class SkillContentRules {

    static final int MAX_DESCRIPTION_LENGTH = 200;
    static final int MAX_CONTENT_LENGTH = 200_000;
    static final int MIN_CONTENT_LENGTH = 200;

    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#{1,3}\\s+\\S+");
    private static final List<String> ROLE_KEYWORDS =
            List.of("规则", "步骤", "流程", "输出", "要求", "职责", "角色");

    private SkillContentRules() {
    }

    /** 一条不通过项；{@code rule} 供前端定位与测试断言，{@code hint} 给可操作的修改建议。 */
    record Issue(String rule, String message, String hint) {}

    static Issue descriptionIssue(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            return new Issue(
                    "description",
                    "描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字",
                    "把细节移进正文，描述只保留一句话用途");
        }
        return null;
    }

    static List<Issue> bodyIssues(String content) {
        String body = content == null ? "" : content.strip();
        List<Issue> issues = new ArrayList<>();
        if (body.isBlank()) {
            issues.add(new Issue("required", "提示词正文必填", "补充角色、规则与输出要求"));
            return issues;
        }
        if (body.length() > MAX_CONTENT_LENGTH) {
            issues.add(
                    new Issue(
                            "length",
                            "提示词正文不能超过 " + MAX_CONTENT_LENGTH + " 字",
                            "把长参考资料移到文件包，正文只留规则"));
        }
        if (body.length() < MIN_CONTENT_LENGTH) {
            issues.add(
                    new Issue(
                            "length",
                            "提示词正文过于简短（" + body.length() + " 字），至少需要 "
                                    + MIN_CONTENT_LENGTH + " 字，并说明角色、规则与输出要求",
                            "补齐角色、规则、步骤与输出要求四段"));
        }
        if (!HEADING_PATTERN.matcher(body).find()) {
            issues.add(
                    new Issue(
                            "heading",
                            "提示词正文需要至少一个 Markdown 标题（如 ## 角色 / ## 规则）",
                            "用 ## 角色 / ## 规则 / ## 输出要求 分段"));
        }
        if (ROLE_KEYWORDS.stream().noneMatch(body::contains)) {
            issues.add(
                    new Issue(
                            "role",
                            "提示词正文需要包含角色规则，至少出现“角色 / 规则 / 步骤 / 输出要求”之一",
                            "写明角色定位与可执行规则"));
        }
        return issues;
    }

    static String cleanDescription(String description) {
        Issue issue = descriptionIssue(description);
        if (issue != null) {
            throw new PayrollException(issue.message());
        }
        return description == null ? "" : description.trim();
    }

    static String requireBody(String content) {
        List<Issue> issues = bodyIssues(content);
        if (!issues.isEmpty()) {
            throw new PayrollException(issues.get(0).message());
        }
        return content.strip();
    }
}
