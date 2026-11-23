package io.agentscope.examples.payroll.skill;

import java.util.List;

/**
 * 技能目录条目（规格 4.1）：只含元数据，正文按需加载。
 *
 * @param contentHash 正文哈希，用于智能体缓存指纹；正文变化即失效
 * @param contentLength 去掉 frontmatter 后的正文字符数
 * @param resourceHash 资源指纹（路径 + 各文件内容哈希聚合），空表示无资源；
 *     只改参考文件、正文不变时正文哈希不变，靠它让智能体缓存失效
 * @param executionEnabled 是否允许 skill_run；与正文字段一起进入缓存指纹
 */
public record SkillManifest(
        String id,
        String name,
        String description,
        List<String> tags,
        String version,
        String contentHash,
        int contentLength,
        String resourceHash,
        boolean executionEnabled,
        String source) {

    public SkillManifest {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        version = version == null ? "" : version.trim();
        contentHash = contentHash == null ? "" : contentHash;
        resourceHash = resourceHash == null ? "" : resourceHash;
        source = source == null ? "" : source;
    }

    public SkillManifest withResourceHash(String value) {
        return new SkillManifest(
                id,
                name,
                description,
                tags,
                version,
                contentHash,
                contentLength,
                value,
                executionEnabled,
                source);
    }

    /**
     * 目录行：id：描述（版本），供系统提示词稳定渲染。
     *
     * <p>不渲染正文长度：它是 {@code strip()} 归一后的字符数，同一份内容在 Nacos 与库里差 1 个字，
     * 于是切库当天整份技能目录集体改字节，还会把按字符数计算的注入截断点推走一行（规格 §17 V-12）。
     */
    public String catalogLine() {
        StringBuilder line = new StringBuilder("- ").append(id);
        if (!description.isBlank()) {
            line.append("：").append(description);
        }
        if (!version.isBlank()) {
            line.append("（v").append(version).append("）");
        }
        return line.toString();
    }
}
