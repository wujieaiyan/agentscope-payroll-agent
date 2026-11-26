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
 * @param createdBy 首次写入者；Nacos/内置没有该列，为 {@code null}
 * @param createdAt 首次写入时间；未知为 0
 * @param updatedBy 最后写入者；Nacos/内置为 {@code null}，db 源未知写者为空串
 * @param needsData 是否声明初始化脚本（三态），{@code null} 表示来源无法判定
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillDetail(
        String id,
        String description,
        String content,
        String source,
        boolean builtin,
        boolean overridden,
        long createdAt,
        long updatedAt,
        List<SkillFilePackage.Summary> files,
        boolean executionEnabled,
        Map<String, Object> metadata,
        String revision,
        int versionNo,
        String createdBy,
        String updatedBy,
        Boolean needsData) {

    /** 兼容构造：旧调用点只有 {@code updatedAt}，创建时间按未知处理。 */
    public SkillDetail(
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
        this(
                id,
                description,
                content,
                source,
                builtin,
                overridden,
                0L,
                updatedAt,
                files,
                executionEnabled,
                metadata,
                revision,
                versionNo,
                null,
                null,
                null);
    }

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
