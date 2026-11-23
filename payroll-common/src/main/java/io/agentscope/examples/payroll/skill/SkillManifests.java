package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** manifest 构造与哈希工具：正文哈希用 SHA-256，变化即让智能体缓存指纹失效。 */
public final class SkillManifests {

    private SkillManifests() {
    }

    public static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    public static SkillManifest fromSkill(AgentSkill skill) {
        if (skill == null) {
            return null;
        }
        String content = skill.getSkillContent() == null ? "" : skill.getSkillContent();
        return new SkillManifest(
                skill.getName(),
                skill.getName(),
                skill.getDescription(),
                tags(skill.getMetadata()),
                version(skill.getMetadata()),
                contentHash(content),
                content.length(),
                resourceHash(skill.getResources()),
                SkillExecutions.executionEnabled(skill),
                skill.getSource());
    }

    public static SkillManifest forId(
            String id, String description, String content, Map<String, Object> metadata, String source) {
        String effectiveDescription =
                description == null || description.isBlank()
                        ? RoleSkills.description(id)
                        : description;
        String body = content == null ? "" : content;
        return new SkillManifest(
                id,
                id,
                effectiveDescription,
                tags(metadata),
                version(metadata),
                contentHash(body),
                body.length(),
                "",
                SkillExecutions.executionEnabled(metadata),
                source);
    }

    /**
     * 资源指纹：路径与各自内容哈希排序后聚合。文件集合相同则指纹相同（与挂载顺序无关），
     * 任一文件改名或改内容都会变化。空集合返回空串，供目录阶段区分「未知」与「无资源」。
     */
    public static String resourceHash(Map<String, String> resources) {
        if (resources == null || resources.isEmpty()) {
            return "";
        }
        Map<String, String> hashes = new LinkedHashMap<>(resources.size());
        resources.forEach((path, content) -> hashes.put(path, contentHash(content)));
        return resourceHashOf(hashes);
    }

    /**
     * 已经持有逐文件指纹时的聚合入口。库侧（{@code skill_resource.sha256}）存的就是同一算法算出的
     * sha256，必须共用这条聚合语句，否则同一批字节在 Nacos 源与 db 源得到两个指纹，切换存储时
     * 智能体缓存会整批重建。
     */
    public static String resourceHashOf(Map<String, String> pathToContentHash) {
        if (pathToContentHash == null || pathToContentHash.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>(pathToContentHash.size());
        pathToContentHash.forEach((path, hash) -> pairs.add(path + ':' + hash));
        pairs.sort(String::compareTo);
        return contentHash(String.join(";", pairs));
    }

    /** 从任意 metadata 提取标签；支持逗号分隔字符串与集合。 */
    public static List<String> tags(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        Object raw = metadata.get("tags");
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addTag(values, item);
            }
        } else {
            for (String piece : String.valueOf(raw).split("[,，\\s]+")) {
                addTag(values, piece);
            }
        }
        return List.copyOf(values);
    }

    private static void addTag(List<String> values, Object item) {
        if (item == null) {
            return;
        }
        String tag = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
        if (!tag.isEmpty() && !values.contains(tag)) {
            values.add(tag);
        }
    }

    public static String version(Map<String, Object> metadata) {
        if (metadata == null) {
            return "";
        }
        Object raw = metadata.get("version");
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /** 目录版本号：所有 manifest 哈希合并，版本不变不重建提示词。 */
    public static String catalogVersion(Collection<SkillManifest> manifests) {
        List<String> pairs = new ArrayList<>(manifests.size());
        for (SkillManifest manifest : manifests) {
            pairs.add(manifest.id() + ':' + manifest.contentHash());
        }
        pairs.sort(String::compareTo);
        return contentHash(String.join(";", pairs));
    }
}
