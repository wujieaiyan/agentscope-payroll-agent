package io.agentscope.examples.payroll.skill;

import java.util.List;

/**
 * 技能目录条目（规格 4.1）：只含元数据，正文按需加载。
 *
 * @param contentHash 正文哈希，用于智能体缓存指纹；正文变化即失效
 * @param contentLength 去掉 frontmatter 后的正文字符数
 * @param hasResources 是否带文件包；目录构建阶段延迟判定
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
        boolean hasResources,
        boolean executionEnabled,
        String source) {

    public SkillManifest {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        version = version == null ? "" : version.trim();
        contentHash = contentHash == null ? "" : contentHash;
        source = source == null ? "" : source;
    }

    public SkillManifest withHasResources(boolean value) {
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

    /** 目录行：id：描述（版本, 大小），供系统提示词稳定渲染。 */
    public String catalogLine() {
        StringBuilder line = new StringBuilder("- ").append(id);
        if (!description.isBlank()) {
            line.append("：").append(description);
        }
        String size = contentLength >= 1024
                ? (contentLength / 1024) + "KB"
                : contentLength + "B";
        line.append("（").append(version.isBlank() ? size : "v" + version + ", " + size);
        line.append("）");
        return line.toString();
    }
}
