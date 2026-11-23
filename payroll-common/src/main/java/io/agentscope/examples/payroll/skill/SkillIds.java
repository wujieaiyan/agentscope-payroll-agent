package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.regex.Pattern;

/**
 * 技能 ID 的唯一规则：小写字母开头，只允许小写字母、数字与短横线，2–64 位。
 *
 * <p>Nacos 的 {@code <skill-id>.md} 配置名、ZIP/Git 项目目录名与 {@code skill_definition.skill_id}
 * 列宽共用这一处定义。校验原先只活在 {@code NacosSkillRegistry} 里，迁库时必须在另一个仓储里重写一遍，
 * 两处规则一旦漂移，就会出现「Nacos 里合法、库里读不到」的技能。
 */
public final class SkillIds {

    /** 长度上限，与 {@code V22__skill_definition.sql} 的 {@code skill_id VARCHAR(64)} 同值。 */
    public static final int MAX_ID_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    private SkillIds() {}

    /** 去空白后校验，不合法抛 {@link PayrollException}；不做大小写折叠，与现网写入侧一致。 */
    public static String normalize(String id) {
        String value = id == null ? "" : id.trim();
        if (value.isBlank()) {
            throw new PayrollException("技能 ID 必填");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new PayrollException("技能 ID 不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        if (!isValid(value)) {
            throw new PayrollException(
                    "技能 ID 只能包含小写字母、数字和短横线，且以小写字母开头（2–64 位）");
        }
        return value;
    }

    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }
}
