package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技能正文质量门槛：管理台保存与项目导入共用，避免两条入口规则漂移。
 *
 * <p>正文会直接注入系统提示词；过短或没有结构的正文会让模型自行补全角色设定。
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

    static String cleanDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new PayrollException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        return value;
    }

    static String requireBody(String content) {
        String body = content == null ? "" : content.strip();
        if (body.isBlank()) {
            throw new PayrollException("提示词正文必填");
        }
        if (body.length() > MAX_CONTENT_LENGTH) {
            throw new PayrollException("提示词正文不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        if (body.length() < MIN_CONTENT_LENGTH) {
            throw new PayrollException(
                    "提示词正文过于简短（" + body.length() + " 字），至少需要 "
                            + MIN_CONTENT_LENGTH + " 字，并说明角色、规则与输出要求");
        }
        if (!HEADING_PATTERN.matcher(body).find()) {
            throw new PayrollException("提示词正文需要至少一个 Markdown 标题（如 ## 角色 / ## 规则）");
        }
        if (ROLE_KEYWORDS.stream().noneMatch(body::contains)) {
            throw new PayrollException(
                    "提示词正文需要包含角色规则，至少出现“角色 / 规则 / 步骤 / 输出要求”之一");
        }
        return body;
    }
}
