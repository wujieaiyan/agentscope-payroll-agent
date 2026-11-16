package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能包伴生文件：Nacos dataId 为 {@code <skill-id>.files.json}，v2 格式
 * {@code {"version":2,"mdHash":"...","meta":{...},"files":[{"path","content"}]}}。
 *
 * <p>{@code mdHash} 绑定正文版本，正文变更后未重发文件包时文件被忽略，避免读到
 * 与提示词不匹配的参考资料。{@code meta} 保留 SKILL.md 原始 frontmatter，
 * 供管理台保存正文时不被覆盖。v2 兼容读取 v1，写入统一升到 v2。文件只从内存读取，不落盘。
 */
public final class SkillFilePackage {

    public static final String DATA_ID_SUFFIX = ".files.json";
    public static final int VERSION = 2;
    public static final int MIN_COMPAT_VERSION = 1;
    public static final String KIND_REFERENCE = "reference";
    public static final String KIND_SCRIPT = "script";
    public static final String KIND_ASSET = "asset";

    public static final String SKILL_MD = "SKILL.md";
    public static final String PROMPT_MD = "PROMPT.md";
    public static final String README_MD = "README.md";
    public static final String LICENSE = "LICENSE";
    public static final String SETUP_SH = "setup.sh";

    private static final Set<String> ROOT_FILES =
            Set.of(SKILL_MD, PROMPT_MD, README_MD, LICENSE, SETUP_SH);
    private static final List<String> DIRECTORY_PREFIXES =
            List.of("references/", "scripts/", "assets/");
    private static final String REFERENCES_PREFIX = "references/";
    private static final String SCRIPTS_PREFIX = "scripts/";
    private static final String ASSETS_PREFIX = "assets/";
    private static final int MAX_PATH_LENGTH = 200;
    private static final Pattern PATH_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern CONTROL_PATTERN =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final Set<String> SCRIPT_SUFFIXES = Set.of(".py", ".sh", ".js");
    private static final TypeReference<LinkedHashMap<String, Object>> META_TYPE =
            new TypeReference<>() {};
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ObjectMapper JSON = new ObjectMapper();

    public record SkillFile(String path, String content) {
    }

    /** 管理台摘要：只回路径、大小与类型，不回正文。 */
    public record Summary(String path, int size, String kind) {
    }

    public record Parsed(String mdHash, Map<String, Object> meta, List<SkillFile> files) {

        public Parsed(String mdHash, List<SkillFile> files) {
            this(mdHash, Map.of(), files);
        }
    }

    private SkillFilePackage() {
    }

    public static String dataId(String skillId) {
        return skillId + DATA_ID_SUFFIX;
    }

    /** 正文哈希：忽略首尾空白与 frontmatter，取 SHA-256 前 16 位十六进制。 */
    public static String mdHash(String content) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (content == null ? "" : content.strip())
                                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int value = hash[i] & 0xFF;
                hex.append(HEX[value >>> 4]).append(HEX[value & 0x0F]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 解析文件包；JSON 损坏或版本不支持时返回空，由调用方告警并忽略。 */
    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null) {
                return Optional.empty();
            }
            int version = root.path("version").asInt(0);
            if (version < MIN_COMPAT_VERSION || version > VERSION) {
                return Optional.empty();
            }
            String mdHash = root.path("mdHash").asText("");
            if (mdHash.isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            JsonNode metaNode = root.path("meta");
            if (metaNode.isObject()) {
                meta.putAll(JSON.convertValue(metaNode, META_TYPE));
            }
            List<SkillFile> files = new ArrayList<>();
            JsonNode items = root.path("files");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    files.add(
                            new SkillFile(
                                    item.path("path").asText(""),
                                    item.path("content").asText("")));
                }
            }
            return Optional.of(
                    new Parsed(
                            mdHash,
                            Collections.unmodifiableMap(meta),
                            List.copyOf(files)));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static String compose(String mdHash, List<SkillFile> files) {
        return compose(mdHash, Map.of(), files);
    }

    public static String compose(String mdHash, Map<String, Object> meta, List<SkillFile> files) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("mdHash", mdHash == null ? "" : mdHash);
        if (meta == null || meta.isEmpty()) {
            root.putObject("meta");
        } else {
            root.set("meta", JSON.valueToTree(meta));
        }
        ArrayNode items = root.putArray("files");
        for (SkillFile file : files) {
            ObjectNode item = items.addObject();
            item.put("path", file.path());
            item.put("content", file.content());
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("技能文件包序列化失败", e);
        }
    }

    /** 校验路径白名单、路径格式、单文件与总量上限，并归一化路径。 */
    public static List<SkillFile> normalizeAll(
            List<SkillFile> files, SkillFileSettings settings) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        int maxFiles = settings.maxFiles();
        if (files.size() > maxFiles) {
            throw new PayrollException("技能文件最多 " + maxFiles + " 个，当前 " + files.size() + " 个");
        }
        Map<String, SkillFile> unique = new LinkedHashMap<>();
        long totalBytes = 0;
        for (SkillFile file : files) {
            if (file == null) {
                throw new PayrollException("技能文件不能为空");
            }
            String path = normalizePath(file.path());
            String content = file.content() == null ? "" : file.content();
            if (unique.containsKey(path)) {
                throw new PayrollException("技能文件路径重复：" + path);
            }
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            if (size > settings.maxFileBytes()) {
                throw new PayrollException(
                        "文件 " + path + " 为 " + size + " 字节，超过单文件上限 "
                                + settings.maxFileBytes() + " 字节");
            }
            totalBytes += size;
            unique.put(path, new SkillFile(path, content));
        }
        if (totalBytes > settings.maxTotalBytes()) {
            throw new PayrollException(
                    "技能文件总量为 " + totalBytes + " 字节，超过上限 "
                            + settings.maxTotalBytes() + " 字节");
        }
        return List.copyOf(unique.values());
    }

    /**
     * 允许的包内路径：固定根文件，或 {@code references/**}、{@code scripts/**}、
     * {@code assets/**} 下的相对路径。{@code data/**} 属于沙箱工作区，不进包。
     */
    public static String normalizePath(String raw) {
        String path = raw == null ? "" : raw.trim();
        if (path.isBlank()) {
            throw new PayrollException("文件路径必填");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new PayrollException("文件路径不能超过 " + MAX_PATH_LENGTH + " 个字符");
        }
        if (path.indexOf('\\') >= 0 || CONTROL_PATTERN.matcher(path).find()) {
            throw new PayrollException("文件路径不能包含反斜杠或控制字符：" + path);
        }
        if (path.startsWith("/")) {
            throw new PayrollException("文件路径必须是相对路径：" + path);
        }
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new PayrollException(
                    "文件路径只能包含字母、数字、点、下划线、短横线和斜杠：" + path);
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new PayrollException("文件路径不能包含 . 或 .. 片段：" + path);
            }
        }
        if (ROOT_FILES.contains(path)) {
            return path;
        }
        if (DIRECTORY_PREFIXES.stream().noneMatch(path::startsWith)) {
            throw new PayrollException(
                    "文件只能放在 references/、scripts/、assets/ 目录下，或使用固定根文件 "
                            + String.join("、", ROOT_FILES.stream().sorted().toList())
                            + "："
                            + path);
        }
        if (path.startsWith(SCRIPTS_PREFIX)
                && SCRIPT_SUFFIXES.stream().noneMatch(path::endsWith)) {
            throw new PayrollException("脚本只支持 .py / .sh / .js：" + path);
        }
        return path;
    }

    /**
     * skill_read 可读路径：说明文件与 references/scripts；{@code setup.sh} 与
     * {@code assets/**} 不进模型上下文。
     */
    public static String normalizeReadPath(String raw) {
        String path = normalizePath(raw);
        if (!isReadable(path)) {
            throw new PayrollException(
                    "只能读取 references/**、scripts/** 与 SKILL.md/PROMPT.md/README.md/LICENSE："
                            + path);
        }
        return path;
    }

    public static boolean isReadable(String path) {
        if (path == null) {
            return false;
        }
        if (ROOT_FILES.contains(path)) {
            return !SETUP_SH.equals(path);
        }
        return path.startsWith(REFERENCES_PREFIX) || path.startsWith(SCRIPTS_PREFIX);
    }

    public static String kind(String path) {
        if (path == null) {
            return KIND_REFERENCE;
        }
        if (path.startsWith(SCRIPTS_PREFIX)) {
            return KIND_SCRIPT;
        }
        if (path.startsWith(ASSETS_PREFIX)) {
            return KIND_ASSET;
        }
        return KIND_REFERENCE;
    }

    public static Summary summary(SkillFile file) {
        return new Summary(
                file.path(),
                (file.content() == null ? "" : file.content()).getBytes(StandardCharsets.UTF_8).length,
                kind(file.path()));
    }
}
