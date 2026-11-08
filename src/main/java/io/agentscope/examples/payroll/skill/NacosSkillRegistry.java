package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能管理注册表：Nacos 中 dataId 为 {@code <skill-id>.md} 的配置就是可编辑技能。
 *
 * <p>列表把 Nacos 技能与三个内置角色技能合并：同名 Nacos 技能覆盖内置版本，
 * 删除覆盖后自动回退内置提示词。读路径带 TTL 缓存，写操作直接发布/删除 Nacos 配置。
 */
public final class NacosSkillRegistry {

    public static final String DATA_ID_SUFFIX = ".md";
    public static final String SOURCE_NACOS = "nacos";
    public static final String SOURCE_BUILTIN = "builtin";

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRegistry.class);
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 200_000;

    /** 管理台列表项；contentLength 为去掉 frontmatter 后的正文字数。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillSummary(
            String id,
            String description,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt,
            int contentLength) {
    }

    /** 编辑详情：content 为正文（不含 frontmatter），保存时由服务端重新生成 frontmatter。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SkillDetail(
            String id,
            String description,
            String content,
            String source,
            boolean builtin,
            boolean overridden,
            long updatedAt) {
    }

    private record RemoteSkill(
            String id, String raw, String description, String content, long updatedAt) {
    }

    private record Snapshot(List<RemoteSkill> remotes, Map<String, RemoteSkill> byId, long fetchedAt) {
    }

    private final NacosSkillSettings settings;
    private final NacosConfigClient client;
    private final long ttlMillis;

    private volatile Snapshot snapshot;

    public NacosSkillRegistry() {
        this(NacosSkillSettings.fromEnvironment());
    }

    NacosSkillRegistry(NacosSkillSettings settings) {
        this.settings = settings;
        this.client = new NacosConfigClient(settings);
        this.ttlMillis = settings.ttl().toMillis();
    }

    public boolean writable() {
        return settings.enabled();
    }

    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    public List<SkillSummary> list() {
        Snapshot snap = snapshot();
        List<SkillSummary> summaries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : RoleSkills.ids()) {
            RemoteSkill remote = snap.byId().get(id);
            summaries.add(remote == null ? builtinSummary(id) : summary(remote, true));
            seen.add(id);
        }
        snap.remotes().stream()
                .filter(remote -> !seen.contains(remote.id()))
                .sorted(Comparator.comparing(RemoteSkill::id))
                .map(remote -> summary(remote, false))
                .forEach(summaries::add);
        return List.copyOf(summaries);
    }

    public SkillDetail detail(String id) {
        String skillId = normalizeId(id);
        RemoteSkill remote = snapshot().byId().get(skillId);
        boolean builtin = RoleSkills.ids().contains(skillId);
        if (remote != null) {
            return new SkillDetail(
                    skillId,
                    remote.description(),
                    remote.content(),
                    SOURCE_NACOS,
                    builtin,
                    builtin,
                    remote.updatedAt());
        }
        if (builtin) {
            AgentSkill skill = RoleSkills.skill(skillId);
            return new SkillDetail(
                    skillId,
                    skill.getDescription(),
                    skill.getSkillContent(),
                    SOURCE_BUILTIN,
                    true,
                    false,
                    0);
        }
        throw new PayrollException("技能不存在：" + skillId);
    }

    public synchronized SkillSummary create(String id, String description, String content) {
        requireWritable();
        String skillId = normalizeId(id);
        if (exists(skillId)) {
            throw new PayrollException("技能已存在：" + skillId);
        }
        return save(skillId, description, content);
    }

    public synchronized SkillSummary update(String id, String description, String content) {
        requireWritable();
        String skillId = normalizeId(id);
        if (!exists(skillId)) {
            throw new PayrollException("技能不存在：" + skillId);
        }
        return save(skillId, description, content);
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        String skillId = normalizeId(id);
        RemoteSkill remote = snapshot().byId().get(skillId);
        if (remote == null) {
            if (RoleSkills.ids().contains(skillId)) {
                throw new PayrollException("内置技能没有 Nacos 覆盖版本，不能删除");
            }
            throw new PayrollException("技能不存在：" + skillId);
        }
        if (!client.remove(skillId + DATA_ID_SUFFIX)) {
            throw new PayrollException(
                    "删除 Nacos 配置 "
                            + skillId
                            + DATA_ID_SUFFIX
                            + " 失败，请检查 Nacos 连接与账号权限");
        }
        snapshot = null;
        log.info("[Skill] 已删除 Nacos 技能 {}", skillId);
        return true;
    }

    private SkillSummary save(String skillId, String description, String content) {
        String cleanDescription = description == null ? "" : description.trim();
        if (cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new PayrollException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        String cleanContent = content == null ? "" : content.strip();
        if (cleanContent.isBlank()) {
            throw new PayrollException("提示词正文必填");
        }
        if (cleanContent.length() > MAX_CONTENT_LENGTH) {
            throw new PayrollException("提示词正文不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        String markdown = composeMarkdown(skillId, cleanDescription, cleanContent);
        if (!client.publish(skillId + DATA_ID_SUFFIX, markdown, "markdown")) {
            throw new PayrollException(
                    "写入 Nacos 配置 "
                            + skillId
                            + DATA_ID_SUFFIX
                            + " 失败，请检查 Nacos 连接与账号权限");
        }
        snapshot = null;
        boolean builtin = RoleSkills.ids().contains(skillId);
        log.info("[Skill] 已保存 Nacos 技能 {}（{} 字）", skillId, cleanContent.length());
        return new SkillSummary(
                skillId,
                cleanDescription,
                SOURCE_NACOS,
                builtin,
                builtin,
                System.currentTimeMillis(),
                cleanContent.length());
    }

    private boolean exists(String id) {
        return RoleSkills.ids().contains(id) || snapshot().byId().containsKey(id);
    }

    private Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (current != null && now - current.fetchedAt() < ttlMillis) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && now - current.fetchedAt() < ttlMillis) {
                return current;
            }
            current = load();
            snapshot = current;
            return current;
        }
    }

    private Snapshot load() {
        if (!settings.enabled()) {
            return new Snapshot(List.of(), Map.of(), System.currentTimeMillis());
        }
        List<NacosConfigClient.ConfigEntry> entries = client.list("*" + DATA_ID_SUFFIX);
        Map<String, RemoteSkill> byId = new LinkedHashMap<>();
        for (NacosConfigClient.ConfigEntry entry : entries) {
            String dataId = entry.dataId();
            if (!dataId.startsWith(".") && !dataId.endsWith(DATA_ID_SUFFIX)) {
                continue;
            }
            String id = dataId.substring(0, dataId.length() - DATA_ID_SUFFIX.length());
            if (!isValidId(id)) {
                continue;
            }
            RemoteSkill remote = parseRemote(id, entry);
            byId.put(id, remote);
        }
        log.info("[Skill] 从 Nacos 加载 {} 个技能配置", byId.size());
        return new Snapshot(List.copyOf(byId.values()), Map.copyOf(byId), System.currentTimeMillis());
    }

    private RemoteSkill parseRemote(String id, NacosConfigClient.ConfigEntry entry) {
        String raw = entry.content();
        String content = raw;
        String description = "";
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(raw);
            if (parsed != null) {
                if (parsed.hasFrontmatter()
                        && parsed.getContent() != null
                        && !parsed.getContent().isBlank()) {
                    content = parsed.getContent();
                }
                if (parsed.getMetadata() != null) {
                    Object value = parsed.getMetadata().get("description");
                    if (value != null) {
                        description = value.toString().trim();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 解析 Nacos 技能 {} 失败，按纯文本使用：{}", id, e.getMessage());
        }
        if (description.isBlank() && RoleSkills.ids().contains(id)) {
            description = RoleSkills.description(id);
        }
        return new RemoteSkill(id, raw, description, content, entry.updatedAt());
    }

    private static SkillSummary summary(RemoteSkill remote, boolean builtin) {
        return new SkillSummary(
                remote.id(),
                remote.description(),
                SOURCE_NACOS,
                builtin,
                builtin,
                remote.updatedAt(),
                remote.content().length());
    }

    private static SkillSummary builtinSummary(String id) {
        AgentSkill skill = RoleSkills.skill(id);
        return new SkillSummary(
                id,
                skill.getDescription(),
                SOURCE_BUILTIN,
                true,
                false,
                0,
                skill.getSkillContent().length());
    }

    private static String composeMarkdown(String id, String description, String content) {
        StringBuilder markdown = new StringBuilder("---\n");
        markdown.append("name: ").append(id).append('\n');
        if (!description.isBlank()) {
            markdown.append("description: ")
                    .append(quoteYaml(description))
                    .append('\n');
        }
        markdown.append("---\n").append(content).append('\n');
        return markdown.toString();
    }

    private static String quoteYaml(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + "\"";
    }

    private static String normalizeId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.isBlank()) {
            throw new PayrollException("技能 ID 必填");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new PayrollException("技能 ID 不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        if (!isValidId(value)) {
            throw new PayrollException(
                    "技能 ID 只能包含小写字母、数字和短横线，且以小写字母开头（2–64 位）");
        }
        return value;
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    private void requireWritable() {
        if (!settings.enabled()) {
            throw new PayrollException(
                    "Nacos 未启用（设置 PAYROLL_NACOS_SERVER），无法管理技能");
        }
    }
}
