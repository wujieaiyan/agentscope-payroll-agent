package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

/**
 * 编辑详情：content 为正文（不含 frontmatter），保存时由服务端重新生成 frontmatter。
 *
 * @param revision 编辑器的乐观并发令牌，保存时原样回传；来源没有行级 revision 时为空串
 * @param versionNo 当前正文在 {@code skill_version} 里的序号，写审计要带它（规格 §13-6）；
 *     Nacos 源与内置技能没有版本行，恒为 0
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillDetail(
        String id,
        String description,
        String content,
        String source,
        boolean builtin,
        boolean overridden,
        long updatedAt,
        List<SkillFilePackage.Summary> files,
        boolean executionEnabled,
        Map<String, Object> metadata,
        String revision,
        int versionNo) {

    public SkillDetail(
            String id,
            String description,
            String content,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            List<SkillFilePackage.Summary> files,
            boolean executionEnabled) {
        this(
                id,
                description,
                content,
                source,
                builtin,
                overridden,
                updatedAt,
                files,
                executionEnabled,
                Map.of(),
                "",
                0);
    }
}
