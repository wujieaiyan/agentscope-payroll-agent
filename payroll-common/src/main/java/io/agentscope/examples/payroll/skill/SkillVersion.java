package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 版本时间线的一行（规格 §13 的 {@code GET /api/admin/skills/{id}/versions}）。
 *
 * <p>只回定位与说明，不回正文：正文走 {@code detail}，否则时间线一打开就要把每个版本的正文字节整批传到浏览器。
 *
 * @param changeNote 该版本的「变更说明」，来自管理台保存时填的字段；没填为空串
 * @param createdBy 写这条版本行的操作者；库内是 {@code NULL}（迁移灌入、窄签名写入）时回读为空串。
 *     要查得更细（谁在什么时候改的、改前改后是什么）仍走审计页，按 {@code skill.update} + {@code versionNo} 过滤
 * @param createdAt 该版本行落库的 epoch 毫秒（0 = 库里没有该列的值）
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillVersion(
        int versionNo,
        String changeNote,
        int contentLength,
        String createdBy,
        long createdAt,
        boolean current) {
}
