package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * 历史版本只读视图（规格 §6.3.4）：正文 + 该版本的文件清单 + 是否当前版本。
 *
 * <p>文件清单取的是 {@code skill_resource} 中该版本的行，不是当前版本：回滚与对比都以版本为单位，
 * 拿当前清单冒充历史会让「这版到底带哪些文件」失真。
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillVersionContent(
        int versionNo,
        String content,
        String changeNote,
        String createdBy,
        long createdAt,
        int contentLength,
        boolean current,
        List<SkillFilePackage.Summary> files) {}
