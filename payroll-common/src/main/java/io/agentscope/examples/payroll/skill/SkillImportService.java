package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能项目导入：Git 浅克隆或 ZIP 解压到临时目录后交给 {@link SkillProjectParser}，
 * 校验通过再整体写入 {@link SkillStore}。
 *
 * <p>导入只读取公共仓库（https + 主机白名单）；ZIP 拒绝绝对路径、{@code ..}、
 * 符号链接与加密条目，并限制解压总量，避免把大仓库或恶意压缩包塞进技能存储。
 * 临时目录在导入结束后删除；{@code data/} 不参与导入。
 */
public final class SkillImportService {

    private static final Logger log = LoggerFactory.getLogger(SkillImportService.class);
    private static final int GIT_TIMEOUT_SECONDS = 120;
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final int MAX_ENTRY_NAME_LENGTH = 1_024;
    private static final long MAX_EXTRACT_BYTES = 64L * 1024 * 1024;

    private final SkillStore store;
    private final SkillFileSettings fileSettings;
    private final SkillImportSettings settings;

    public SkillImportService(
            SkillStore store,
            SkillFileSettings fileSettings,
            SkillImportSettings settings) {
        this.store = store;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
        this.settings = settings == null ? SkillImportSettings.defaults() : settings;
    }

    /**
     * 导入预览/结果；dryRun 时不写存储，只返回解析结果与冲突状态。
     *
     * @param source 导入来源（{@code git}/{@code zip}），预览响应要标注来源卡片
     * @param hashes 逐文件 sha256（与 {@code skill_resource.sha256} 同算法）；预览拿它和当前版本比对出
     *     新增/覆盖/不变，避免让控制器重新读一遍正文
     */
    public record ImportResult(
            String id,
            String commitSha,
            String description,
            Map<String, Object> metadata,
            List<SkillFilePackage.Summary> files,
            String setupCommand,
            long dataMaxBytes,
            List<String> warnings,
            boolean existing,
            boolean dryRun,
            String source,
            Map<String, String> hashes) {

        /** 加来源与指纹之前的构造点（测试与内部调用）。 */
        public ImportResult(
                String id,
                String commitSha,
                String description,
                Map<String, Object> metadata,
                List<SkillFilePackage.Summary> files,
                String setupCommand,
                long dataMaxBytes,
                List<String> warnings,
                boolean existing,
                boolean dryRun) {
            this(
                    id,
                    commitSha,
                    description,
                    metadata,
                    files,
                    setupCommand,
                    dataMaxBytes,
                    warnings,
                    existing,
                    dryRun,
                    "",
                    Map.of());
        }
    }

    public ImportResult importGit(
            String url, String ref, String id, boolean overwrite, boolean dryRun) {
        return importGit(url, ref, id, overwrite, dryRun, null, "");
    }

    /**
     * 管理台导入的完整形状：Git 拉下来是整包覆盖，最容易盖掉别人刚改的一版，所以它也吃行级令牌。
     *
     * @param expectedRevision 覆盖写时的行令牌；空串/{@code null} 跳过检查（灰度期允许缺失）
     * @param operator 写入者，进 {@code created_by}，让时间线区分「谁从仓库灌的」与「谁在页面上手改的」
     */
    public ImportResult importGit(
            String url,
            String ref,
            String id,
            boolean overwrite,
            boolean dryRun,
            String expectedRevision,
            String operator) {
        URI uri = validateGitUrl(url);
        Path temp = createTempDirectory("payroll-skill-git-");
        try {
            Path cloneDir = temp.resolve(repoName(uri));
            String commitSha = clone(uri, ref, cloneDir);
            SkillProjectParser.ParsedProject project =
                    SkillProjectParser.parse(projectRoot(cloneDir), fileSettings, id);
            return commit(project, commitSha, "git", overwrite, dryRun, expectedRevision, operator);
        } finally {
            deleteRecursively(temp);
        }
    }

    public ImportResult importZip(Path archive, String id, boolean overwrite, boolean dryRun) {
        return importZip(archive, id, overwrite, dryRun, null, "");
    }

    /** 同 {@link #importGit(String, String, String, boolean, boolean, String, String)}。 */
    public ImportResult importZip(
            Path archive,
            String id,
            boolean overwrite,
            boolean dryRun,
            String expectedRevision,
            String operator) {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new PayrollException("请选择要导入的 ZIP 文件");
        }
        try {
            long size = Files.size(archive);
            if (size > settings.maxZipBytes()) {
                throw new PayrollException(
                        "ZIP 为 " + size + " 字节，超过上限 " + settings.maxZipBytes() + " 字节");
            }
        } catch (IOException e) {
            throw new PayrollException("读取 ZIP 文件失败：" + e.getMessage());
        }
        Path temp = createTempDirectory("payroll-skill-zip-");
        try {
            Path root = temp.resolve(archiveBaseName(archive));
            extract(archive, root);
            SkillProjectParser.ParsedProject project =
                    SkillProjectParser.parse(projectRoot(root), fileSettings, id);
            return commit(project, null, "zip", overwrite, dryRun, expectedRevision, operator);
        } finally {
            deleteRecursively(temp);
        }
    }

    private ImportResult commit(
            SkillProjectParser.ParsedProject project,
            String commitSha,
            String source,
            boolean overwrite,
            boolean dryRun,
            String expectedRevision,
            String operator) {
        boolean existing =
                store.list().stream().anyMatch(skill -> skill.id().equals(project.id()));
        if (existing && !overwrite) {
            throw new PayrollConflictException(
                    "技能已存在：" + project.id() + "；覆盖导入需要显式确认");
        }
        if (!dryRun) {
            store.importProject(project, existing, expectedRevision, operator);
        }
        List<SkillFilePackage.Summary> files =
                project.files().stream().map(SkillFilePackage::summary).toList();
        Map<String, String> hashes = new java.util.LinkedHashMap<>();
        for (SkillFilePackage.SkillFile file : project.files()) {
            hashes.put(file.path(), SkillManifests.contentHash(file.content()));
        }
        return new ImportResult(
                project.id(),
                commitSha,
                project.description(),
                project.metadata(),
                files,
                project.setupCommand(),
                project.dataMaxBytes(),
                project.warnings(),
                existing,
                dryRun,
                source,
                Map.copyOf(hashes));
    }

    private URI validateGitUrl(String url) {
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) {
            throw new PayrollException("Git 地址必填");
        }
        if (!raw.regionMatches(true, 0, "https://", 0, "https://".length())) {
            throw new PayrollException("Git 导入只支持 https 地址");
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new PayrollException("Git 地址格式不正确：" + raw);
        }
        if (uri.getHost() == null) {
            throw new PayrollException("Git 地址格式不正确：" + raw);
        }
        if (uri.getUserInfo() != null) {
            throw new PayrollException("Git 地址不能携带用户名或口令");
        }
        String host = uri.getHost();
        if (host == null || !settings.allows(host)) {
            throw new PayrollException(
                    "Git 主机不在白名单：" + (host == null ? "未知" : host)
                            + "；允许 " + String.join("、", settings.allowedHosts()));
        }
        if (uri.getPath() == null || uri.getPath().length() <= 1) {
            throw new PayrollException("Git 地址缺少仓库路径：" + raw);
        }
        return uri;
    }

    private static String clone(URI uri, String ref, Path dir) {
        CloneCommand command =
                Git.cloneRepository()
                        .setURI(uri.toString())
                        .setDirectory(dir.toFile())
                        .setDepth(1)
                        .setCloneSubmodules(false)
                        .setCloneAllBranches(false)
                        .setTimeout(GIT_TIMEOUT_SECONDS);
        if (ref != null && !ref.isBlank()) {
            command.setBranch(ref.trim());
        }
        try (Git git = command.call()) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head == null) {
                throw new PayrollException("Git 克隆完成但无法解析提交 ID");
            }
            return head.name();
        } catch (GitAPIException | IOException e) {
            throw new PayrollException("Git 克隆失败：" + e.getMessage());
        }
    }

    private static String repoName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[.-]+", "");
        return safe.isBlank() ? "skill" : safe;
    }

    private static String archiveBaseName(Path archive) {
        String name = archive.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[.-]+", "");
        return safe.isBlank() ? "skill" : safe;
    }

    /** ZIP 允许 SKILL.md 在根，或包一层目录；一层目录自动剥离。 */
    private static Path projectRoot(Path dir) {
        Path skillMd = dir.resolve(SkillFilePackage.SKILL_MD);
        if (Files.isRegularFile(skillMd)) {
            return dir;
        }
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> items = children.toList();
            if (items.size() == 1 && Files.isDirectory(items.get(0))) {
                return items.get(0);
            }
        } catch (IOException e) {
            throw new PayrollException("读取技能项目目录失败：" + e.getMessage());
        }
        return dir;
    }

    private static void extract(Path archive, Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new PayrollException("创建临时目录失败：" + e.getMessage());
        }
        int count = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new PayrollException("ZIP 条目超过上限 " + MAX_ZIP_ENTRIES + " 个");
                }
                String name = validateEntryName(entry.getName());
                if (!seen.add(name)) {
                    throw new PayrollException("ZIP 包含重复条目：" + name);
                }
                if (entry.isUnixSymlink()) {
                    throw new PayrollException("ZIP 不能包含符号链接：" + name);
                }
                if (entry.isDirectory()) {
                    createDirectories(root, name);
                    continue;
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new PayrollException("ZIP 包含加密或不受支持的条目：" + name);
                }
                long declared = entry.getSize();
                if (declared > 0 && total + declared > MAX_EXTRACT_BYTES) {
                    throw new PayrollException(
                            "ZIP 解压后超过上限 " + MAX_EXTRACT_BYTES + " 字节，请把大数据放到 data/");
                }
                Path target = safeTarget(root, name);
                try {
                    Files.createDirectories(target.getParent());
                } catch (IOException e) {
                    throw new PayrollException("创建解压目录失败：" + name);
                }
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream output =
                                Files.newOutputStream(
                                        target,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE)) {
                    total += transfer(input, output, MAX_EXTRACT_BYTES - total, name);
                }
            }
        } catch (IOException e) {
            throw new PayrollException("读取 ZIP 失败：" + e.getMessage());
        }
        if (count == 0) {
            throw new PayrollException("ZIP 为空");
        }
        log.info("[Skill] 已解压技能 ZIP（{} 个条目，{} 字节）", count, total);
    }

    private static long transfer(InputStream input, OutputStream output, long limit, String name)
            throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            written += read;
            if (written > limit) {
                throw new PayrollException(
                        "ZIP 解压后超过上限，请把大数据放到 data/：" + name);
            }
            output.write(buffer, 0, read);
        }
        return written;
    }

    private static String validateEntryName(String raw) {
        String name = raw == null ? "" : raw;
        if (name.isBlank() || name.length() > MAX_ENTRY_NAME_LENGTH) {
            throw new PayrollException("ZIP 条目名非法：" + name);
        }
        if (name.indexOf('\\') >= 0) {
            throw new PayrollException("ZIP 条目名不能包含反斜杠：" + name);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new PayrollException("ZIP 条目必须是相对路径：" + name);
        }
        for (char ch : name.toCharArray()) {
            if (ch < 0x20) {
                throw new PayrollException("ZIP 条目名不能包含控制字符");
            }
        }
        for (String segment : name.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new PayrollException("ZIP 条目不能包含 . 或 .. 片段：" + name);
            }
        }
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    private static void createDirectories(Path root, String name) {
        try {
            Files.createDirectories(safeTarget(root, name));
        } catch (IOException e) {
            throw new PayrollException("创建解压目录失败：" + name);
        }
    }

    private static Path safeTarget(Path root, String name) {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new PayrollException("ZIP 条目越界：" + name);
        }
        return target;
    }

    private static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new PayrollException("创建临时目录失败：" + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("[Skill] 清理临时目录失败：{}", path);
                                }
                            });
        } catch (IOException e) {
            log.warn("[Skill] 遍历临时目录失败：{}", dir);
        }
    }
}
