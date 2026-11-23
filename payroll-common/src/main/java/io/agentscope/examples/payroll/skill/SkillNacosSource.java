package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos 侧的技能导出视图（规格 §11.2/§11.3）：把 {@code <id>.md} + {@code <id>.files.json}
 * 读成可以直接喂给 {@link SkillStore#importProject} 的项目，并把读不出来的东西如实带出来。
 *
 * <p>不复用 {@link NacosSkillRegistry} 的快照，有两个原因：一是它的 {@code filesOf()} 把坏文件包
 * 吞成空列表 + 一条 WARN，而导入报告必须知道「为什么这个技能没有文件」；二是它带 TTL 缓存与写路径，
 * 迁移工具只要一次性全量读，不该把一个正在服务读流量的实例拉进来。P3 退役 Nacos 时整类删除。
 */
public final class SkillNacosSource {

    private static final Logger log = LoggerFactory.getLogger(SkillNacosSource.class);

    /**
     * 一条 Nacos 技能。{@code files} 已按 {@link SkillFilePackage#normalizeAll} 校验过，可直接落库；
     * {@code fileProblem} 非空表示文件包没读成（JSON 坏、mdHash 与正文不匹配、超配额），
     * 此时正文照旧导入，问题进报告。
     */
    public record RemoteSkill(
            String id,
            String description,
            String body,
            Map<String, Object> metadata,
            boolean executionEnabled,
            List<SkillFilePackage.SkillFile> files,
            String fileProblem) {
    }

    private final NacosSkillSettings settings;
    private final SkillFileSettings fileSettings;
    private final NacosConfigClient client;

    public SkillNacosSource() {
        this(NacosSkillSettings.fromEnvironment(), SkillFileSettings.fromEnvironment());
    }

    SkillNacosSource(NacosSkillSettings settings, SkillFileSettings fileSettings) {
        this.settings = settings;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
        this.client = new NacosConfigClient(settings);
    }

    public String location() {
        return settings.enabled() ? settings.server() + "/" + settings.group() : "代码内置";
    }

    /**
     * 全量读取：内置技能在前（按 {@link RoleSkills#ids()} 顺序），其余按 skillId 字典序。
     *
     * <p>顺序不是展示需要：导入按这个顺序写 {@code sort_order}，而目录顺序直接进入系统提示词。
     * Nacos 运行期目录用的是配置列表的返回顺序（不保证稳定），这里取字典序是为了让切库后的顺序
     * 可复现，代价是可能与切库前不完全一致，见规格 §11.2 的偏差说明。
     */
    public List<RemoteSkill> readSkills() {
        if (!settings.enabled()) {
            throw new PayrollUnavailableException("Nacos 未启用，无法从 Nacos 读取技能");
        }
        NacosConfigClient.ListResult listed =
                client.listResult("*" + NacosSkillRegistry.DATA_ID_SUFFIX);
        if (!listed.ok()) {
            throw new PayrollUnavailableException("列出 Nacos 技能失败：" + listed.warning());
        }
        Map<String, RemoteSkill> remotes = new LinkedHashMap<>();
        for (NacosConfigClient.ConfigEntry entry : listed.entries()) {
            RemoteSkill remote = toRemote(entry);
            if (remote != null) {
                remotes.put(remote.id(), remote);
            }
        }
        List<RemoteSkill> ordered = new ArrayList<>(remotes.size() + RoleSkills.ids().size());
        Set<String> emitted = new LinkedHashSet<>();
        for (String id : RoleSkills.ids()) {
            RemoteSkill remote = remotes.get(id);
            ordered.add(remote == null ? builtin(id) : remote);
            emitted.add(id);
        }
        remotes.values().stream()
                .filter(remote -> !emitted.contains(remote.id()))
                .sorted(Comparator.comparing(RemoteSkill::id))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /**
     * 有 {@code <id>.files.json} 却没有对应 {@code <id>.md} 的残留伴生配置：正文已删但文件包没删。
     *
     * @throws PayrollUnavailableException 列表读取失败——门禁不能把「读不到」当成「没有残留」
     */
    public List<String> listOrphanEnvelopes(Set<String> knownIds) {
        if (!settings.enabled()) {
            throw new PayrollUnavailableException("Nacos 未启用，无法核对文件包残留");
        }
        NacosConfigClient.ListResult listed =
                client.listResult("*" + SkillFilePackage.DATA_ID_SUFFIX);
        if (!listed.ok()) {
            throw new PayrollUnavailableException("列出 Nacos 文件包失败：" + listed.warning());
        }
        List<String> orphans = new ArrayList<>();
        for (NacosConfigClient.ConfigEntry entry : listed.entries()) {
            String dataId = entry.dataId();
            if (dataId == null
                    || dataId.startsWith(".")
                    || !dataId.endsWith(SkillFilePackage.DATA_ID_SUFFIX)) {
                continue;
            }
            String id =
                    dataId.substring(0, dataId.length() - SkillFilePackage.DATA_ID_SUFFIX.length());
            if (SkillIds.isValid(id) && !knownIds.contains(id)) {
                orphans.add(dataId);
            }
        }
        return List.copyOf(orphans);
    }

    private RemoteSkill toRemote(NacosConfigClient.ConfigEntry entry) {
        String dataId = entry.dataId();
        if (dataId == null
                || dataId.startsWith(".")
                || !dataId.endsWith(NacosSkillRegistry.DATA_ID_SUFFIX)) {
            return null;
        }
        String id =
                dataId.substring(0, dataId.length() - NacosSkillRegistry.DATA_ID_SUFFIX.length());
        if (!SkillIds.isValid(id)) {
            return null;
        }
        return parseRemote(id, entry.content());
    }

    /** 解析规则与 {@code NacosSkillRegistry.parseRemote} 一致：没有 frontmatter 就整份当正文。 */
    private RemoteSkill parseRemote(String id, String raw) {
        String body = raw == null ? "" : raw;
        String description = "";
        boolean executionEnabled = false;
        Map<String, Object> metadata = new LinkedHashMap<>();
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(raw);
            if (parsed != null) {
                if (parsed.hasFrontmatter()
                        && parsed.getContent() != null
                        && !parsed.getContent().isBlank()) {
                    body = parsed.getContent();
                }
                if (parsed.getMetadata() != null) {
                    metadata.putAll(parsed.getMetadata());
                    Object value = parsed.getMetadata().get("description");
                    if (value != null) {
                        description = value.toString().trim();
                    }
                    executionEnabled = SkillExecutions.executionEnabled(parsed.getMetadata());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Skill] 解析 Nacos 技能 {} 失败，按纯文本处理：{}", id, e.getMessage());
        }
        if (description.isBlank() && RoleSkills.ids().contains(id)) {
            description = RoleSkills.description(id);
        }
        List<SkillFilePackage.SkillFile> files = List.of();
        String problem = "";
        if (fileSettings.enabled()) {
            try {
                files = readFiles(id, body);
            } catch (PayrollException e) {
                // 文件包问题不阻断正文导入：一个坏 envelope 不该让全量迁移停在第 N 个技能上。
                problem = e.getMessage();
            }
        }
        return new RemoteSkill(
                id, description, body.strip(), metadata, executionEnabled, files, problem);
    }

    /** 内置技能在 Nacos 没有同名配置时，按代码内置版本落库（D10a）。 */
    private static RemoteSkill builtin(String id) {
        AgentSkill skill = RoleSkills.skill(id);
        return new RemoteSkill(
                id,
                skill.getDescription() == null ? "" : skill.getDescription(),
                skill.getSkillContent() == null ? "" : skill.getSkillContent().strip(),
                Map.of(),
                false,
                List.of(),
                "");
    }

    /** 与 {@code NacosSkillRegistry.filesOf} 同样的绑定规则：mdHash 必须等于当前正文的哈希。 */
    private List<SkillFilePackage.SkillFile> readFiles(String id, String body) {
        Optional<String> raw = client.fetch(SkillFilePackage.dataId(id));
        if (raw.isEmpty()) {
            return List.of();
        }
        Optional<SkillFilePackage.Parsed> parsed = SkillFilePackage.parse(raw.get());
        if (parsed.isEmpty()) {
            throw new PayrollException("文件包 JSON 无效或版本不支持");
        }
        String mdHash = SkillFilePackage.mdHash(body);
        if (!parsed.get().mdHash().equals(mdHash)) {
            throw new PayrollException("文件包 mdHash 与正文不一致");
        }
        return SkillFilePackage.normalizeAll(parsed.get().files(), fileSettings);
    }
}
