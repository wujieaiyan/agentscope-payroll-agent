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
 * @param versionNo 当前正文在 {@code skill_version} 的序号；Nacos/内置为 {@code null}（源无版本行）
 * @param updatedBy 最后写入者；Nacos 源没有溯源列，为 {@code null}；db 源未知写者为空串
 * @param needsData 是否声明了初始化脚本（三态）：{@code null} 表示来源无法判定（Nacos/内置），
 *     不能当成 {@code false}——界面要据此显示「未知」而不是「无需数据」
 * @param dataMaxBytes 声明的数据上限（{@code metadata.data.max-bytes}，已按导入口径夹在 2–5 GiB）；
 *     列表要展示「已用 / 上限」，放在摘要里才能避免逐行读详情。Nacos/内置为 {@code null}
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
        String revision,
        Integer versionNo,
        String updatedBy,
        Boolean needsData,
        Long dataMaxBytes) {

    public SkillSummary(
            String id,
            String description,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            int contentLength,
            int fileCount,
            String revision) {
        this(
                id,
                description,
                source,
                builtin,
                overridden,
                updatedAt,
                contentLength,
                fileCount,
                revision,
                null,
                null,
                null,
                null);
    }

    /** 加 {@code dataMaxBytes} 之前的 12 参调用点。 */
    public SkillSummary(
            String id,
            String description,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            int contentLength,
            int fileCount,
            String revision,
            Integer versionNo,
            String updatedBy,
            Boolean needsData) {
        this(
                id,
                description,
                source,
                builtin,
                overridden,
                updatedAt,
                contentLength,
                fileCount,
                revision,
                versionNo,
                updatedBy,
                needsData,
                null);
    }
}
