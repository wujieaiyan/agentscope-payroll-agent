package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 管理台技能列表项。
 *
 * @param contentLength 去掉 frontmatter 后的正文字数
 * @param fileCount 列表阶段固定为 0，文件包在详情/文件接口按需加载
 * @param revision 行级乐观并发令牌，写请求原样回传即可触发 409；Nacos 源是整包发布、没有行级
 *     revision，恒为空串（空串在存储侧等于「不做并发检查」，与不传该参数同义）
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record SkillSummary(
        String id,
        String description,
        String source,
        boolean builtin,
        boolean overridden,
        long updatedAt,
        int contentLength,
        int fileCount,
        String revision) {}
