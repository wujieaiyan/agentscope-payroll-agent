package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 目录式技能项目解析器：Git/ZIP 导入落到临时目录后，由本类转成可写入 {@link SkillStore} 的
 * 正文、frontmatter 元信息与白名单文件包。
 *
 * <p>{@code data/}、{@code .git/}、{@code node_modules/} 整体忽略；根目录只保留固定文件，
 * 其余目录只保留 {@code references/**}、{@code scripts/**}、{@code assets/**}。
 * 非 UTF-8 文件跳过并告警，超限直接失败，避免把大仓库塞进技能存储。
 */
public final class SkillProjectParser {

    public static final long DEFAULT_DATA_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long HARD_DATA_MAX_BYTES = 5L * 1024 * 1024 * 1024;

    private static final Set<String> IGNORED_DIRS = Set.of("data", ".git", "node_modules");
    private static final Set<String> INTERPRETERS =
            Set.of("sh", "bash", "python", "python3", "node");
    private static final Pattern UNSAFE_COMMAND = Pattern.compile("[;|&<>`$]");
    private static final int MAX_WARNING_NAMES = 8;

    public record ParsedProject(
            String id,
            Map<String, Object> metadata,
            String description,
            String body,
            List<SkillFilePackage.SkillFile> files,
            String setupCommand,
            long dataMaxBytes,
            List<String> warnings) {
    }

    private SkillProjectParser() {
    }

    public static ParsedProject parse(Path root, SkillFileSettings settings) {
        return parse(root, settings, null);
    }

    /** {@code overrideId} 非空时优先于 SKILL.md 的 name 与目录名。 */
    public static ParsedProject parse(Path root, SkillFileSettings settings, String overrideId) {
        if (root == null || !Files.isDirectory(root)) {
            throw new PayrollException("技能项目目录不存在");
        }
        SkillFileSettings limits = settings == null ? SkillFileSettings.defaults() : settings;
        Path skillMd = root.resolve(SkillFilePackage.SKILL_MD);
        if (Files.isSymbolicLink(skillMd)) {
            // 导入目录来自第三方仓库，符号链接可能指向宿主文件。
            throw new PayrollException("SKILL.md 不能是符号链接");
        }
        if (!Files.isRegularFile(skillMd)) {
            throw new PayrollException("技能项目缺少 SKILL.md");
        }
        String rawSkill = readText(skillMd, limits.maxFileBytes(), SkillFilePackage.SKILL_MD);
        if (rawSkill == null) {
            throw new PayrollException("SKILL.md 不是 UTF-8 文本");
        }
        MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(rawSkill);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (parsed.getMetadata() != null) {
            metadata.putAll(parsed.getMetadata());
        }
        String body = SkillContentRules.requireBody(parsed.getContent());
        List<String> warnings = new ArrayList<>();
        String id = resolveId(overrideId, metadata, root, warnings);
        String description = description(metadata, warnings);
        if (metadata.containsKey("activation")) {
            warnings.add("activation 首期只展示，不参与技能路由");
        }

        List<String> skipped = new ArrayList<>();
        List<String> binaries = new ArrayList<>();
        List<SkillFilePackage.SkillFile> collected = new ArrayList<>();
        for (String rel : candidateFiles(root)) {
            String normalized;
            try {
                normalized = SkillFilePackage.normalizePath(rel);
            } catch (PayrollException e) {
                skipped.add(rel);
                continue;
            }
            String text = readText(root.resolve(rel), limits.maxFileBytes(), normalized);
            if (text == null) {
                binaries.add(normalized);
                continue;
            }
            collected.add(new SkillFilePackage.SkillFile(normalized, text));
        }
        if (!skipped.isEmpty()) {
            warnings.add("以下文件不在白名单或不是普通文件，已忽略：" + sample(skipped));
        }
        if (!binaries.isEmpty()) {
            warnings.add("以下文件不是 UTF-8 文本，已忽略：" + sample(binaries));
        }

        List<SkillFilePackage.SkillFile> files;
        try {
            files = SkillFilePackage.normalizeAll(collected, limits);
        } catch (PayrollException e) {
            throw new PayrollException(e.getMessage() + "；请把大数据放到 data/ 或知识库");
        }
        Set<String> paths = new LinkedHashSet<>();
        files.forEach(file -> paths.add(file.path()));
        String setup = resolveSetup(metadata, paths);
        long dataMaxBytes = dataMaxBytes(metadata, warnings);
        return new ParsedProject(
                id,
                Collections.unmodifiableMap(metadata),
                description,
                body,
                files,
                setup,
                dataMaxBytes,
                List.copyOf(warnings));
    }

    /** 技能名归一化为 ID：小写、非字母数字转短横线，2–64 位；无法归一化返回 null。 */
    public static String normalizeId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        if (value.length() > SkillIds.MAX_ID_LENGTH) {
            value = value.substring(0, SkillIds.MAX_ID_LENGTH).replaceAll("-+$", "");
        }
        return SkillIds.isValid(value) ? value : null;
    }

    private static String resolveId(
            String overrideId, Map<String, Object> metadata, Path root, List<String> warnings) {
        if (overrideId != null && !overrideId.isBlank()) {
            String explicit = overrideId.trim();
            if (!SkillIds.isValid(explicit)) {
                throw new PayrollException("非法技能 ID：" + explicit);
            }
            return explicit;
        }
        Object name = metadata.get("name");
        String normalized = normalizeId(name == null ? "" : name.toString());
        if (normalized != null) {
            return normalized;
        }
        String dirName = root.getFileName() == null ? "" : root.getFileName().toString();
        String fallback = normalizeId(dirName);
        if (fallback != null) {
            if (name != null && !name.toString().isBlank()) {
                warnings.add("技能名 " + name + " 无法归一化为合法 ID，已回退目录名：" + fallback);
            }
            return fallback;
        }
        throw new PayrollException("无法从技能名或目录名推导合法技能 ID，请在导入时指定 id");
    }

    private static String description(Map<String, Object> metadata, List<String> warnings) {
        Object value = metadata.get("description");
        String text = value == null ? "" : value.toString().trim();
        if (text.length() > SkillContentRules.MAX_DESCRIPTION_LENGTH) {
            warnings.add("描述超过 " + SkillContentRules.MAX_DESCRIPTION_LENGTH + " 字，已截断");
            text = text.substring(0, SkillContentRules.MAX_DESCRIPTION_LENGTH);
        }
        return text;
    }

    private static List<String> candidateFiles(Path root) {
        List<String> paths = new ArrayList<>();
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root)
                                    && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String rel =
                                    root.relativize(file).toString().replace('\\', '/');
                            if (Files.isSymbolicLink(file)) {
                                paths.add(rel + "(符号链接)");
                            } else if (attrs.isRegularFile()) {
                                paths.add(rel);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new PayrollException("读取技能项目失败：" + e.getMessage());
        }
        paths.sort(String::compareTo);
        return paths;
    }

    /** 返回 UTF-8 文本；二进制或非 UTF-8 返回 null，超限直接报错。 */
    private static String readText(Path file, int maxBytes, String name) {
        try {
            long size = Files.size(file);
            if (size > maxBytes) {
                throw new PayrollException(
                        "文件 " + name + " 为 " + size + " 字节，超过单文件上限 " + maxBytes
                                + " 字节；请把大数据放到 data/ 或知识库");
            }
            byte[] bytes = Files.readAllBytes(file);
            CharsetDecoder decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
                if (text.indexOf('\0') >= 0) {
                    return null;
                }
                // Windows 检出可能把脚本转成 CRLF，Linux 沙箱执行 setup 时会报错，入库前统一按 LF 保存。
                return text.replace("\r\n", "\n");
            } catch (CharacterCodingException e) {
                return null;
            }
        } catch (IOException e) {
            throw new PayrollException("读取文件失败：" + name);
        }
    }

    private static String resolveSetup(Map<String, Object> metadata, Set<String> paths) {
        Object raw = metadata.get("setup");
        String command = raw == null ? "" : raw.toString().trim();
        if (command.isEmpty()) {
            if (paths.contains(SkillFilePackage.SETUP_SH)) {
                return "sh " + SkillFilePackage.SETUP_SH;
            }
            String nested = "scripts/" + SkillFilePackage.SETUP_SH;
            return paths.contains(nested) ? "sh " + nested : null;
        }
        if (command.indexOf('\n') >= 0
                || command.indexOf('\r') >= 0
                || command.indexOf('\\') >= 0) {
            throw new PayrollException("setup 只能是一行包内脚本命令：" + command);
        }
        if (UNSAFE_COMMAND.matcher(command).find()) {
            throw new PayrollException("setup 不能包含管道、重定向、变量或命令替换：" + command);
        }
        String script = null;
        for (String token : command.split("\\s+")) {
            if (token.isBlank() || token.startsWith("-") || INTERPRETERS.contains(token)) {
                continue;
            }
            script = token;
            break;
        }
        if (script == null) {
            throw new PayrollException("setup 缺少脚本路径：" + command);
        }
        if (script.startsWith("/") || script.contains("..") || script.indexOf('\\') >= 0) {
            throw new PayrollException("setup 只能指向包内脚本：" + script);
        }
        String target = script.startsWith("./") ? script.substring(2) : script;
        if (!SkillFilePackage.SETUP_SH.equals(target) && !target.startsWith("scripts/")) {
            throw new PayrollException("setup 只能执行 setup.sh 或 scripts/** 下的脚本：" + target);
        }
        if (!paths.contains(target)) {
            throw new PayrollException("setup 指向的脚本不存在：" + target);
        }
        return command;
    }

    private static long dataMaxBytes(Map<String, Object> metadata, List<String> warnings) {
        Object rawData = metadata.get("data");
        Object raw = rawData instanceof Map<?, ?> map ? map.get("max-bytes") : null;
        long value = DEFAULT_DATA_MAX_BYTES;
        if (raw instanceof Number number) {
            value = number.longValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                value = Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                warnings.add("data.max-bytes 不是整数，已使用默认 2GB");
                return DEFAULT_DATA_MAX_BYTES;
            }
        }
        if (raw == null) {
            return DEFAULT_DATA_MAX_BYTES;
        }
        if (value <= 0) {
            warnings.add("data.max-bytes 非正数，已使用默认 2GB");
            return DEFAULT_DATA_MAX_BYTES;
        }
        if (value > HARD_DATA_MAX_BYTES) {
            warnings.add("data.max-bytes 超过硬上限 5GB，已按 5GB 处理");
            return HARD_DATA_MAX_BYTES;
        }
        return value;
    }

    private static String sample(List<String> names) {
        if (names.size() <= MAX_WARNING_NAMES) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, MAX_WARNING_NAMES))
                + " 等 " + names.size() + " 个";
    }
}
