package io.agentscope.examples.payroll.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SKILL.md frontmatter 生成：只重写 {@code name}、{@code description} 与
 * {@code execution.enabled}，其余字段（version/license/type/activation/setup/data 等）
 * 按原顺序原样保留，避免管理台保存正文时丢失导入项目自带的元信息。
 *
 * <p>输出为块状 YAML；标量按需加双引号，description 始终加引号并转义换行。
 */
public final class SkillFrontmatter {

    private static final Pattern PLAIN_SAFE =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_. /-]*$");
    private static final Set<String> RESERVED =
            Set.of("true", "false", "yes", "no", "on", "off", "null", "~");

    private SkillFrontmatter() {
    }

    public static String compose(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            Map<String, Object> metadata) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", id);
        if (description != null && !description.isBlank()) {
            fields.put("description", description);
        }
        if (metadata != null) {
            metadata.forEach(
                    (key, value) -> {
                        if (key == null || value == null) {
                            return;
                        }
                        if ("name".equals(key)
                                || "description".equals(key)
                                || "execution".equals(key)) {
                            return;
                        }
                        fields.put(key, value);
                    });
        }
        Map<String, Object> execution = execution(metadata, executionEnabled);
        if (!execution.isEmpty()) {
            fields.put("execution", execution);
        }
        StringBuilder markdown = new StringBuilder("---\n");
        emitMap(markdown, fields, 0);
        markdown.append("---\n").append(content == null ? "" : content).append('\n');
        return markdown.toString();
    }

    private static Map<String, Object> execution(
            Map<String, Object> metadata, boolean enabled) {
        Map<String, Object> execution = new LinkedHashMap<>();
        Object raw = metadata == null ? null : metadata.get("execution");
        if (raw instanceof Map<?, ?> map) {
            map.forEach(
                    (key, value) -> {
                        if (key == null || value == null) {
                            return;
                        }
                        String name = String.valueOf(key);
                        if (!"enabled".equals(name)) {
                            execution.put(name, value);
                        }
                    });
        }
        if (enabled) {
            execution.put("enabled", true);
        }
        return execution;
    }

    private static void emitMap(StringBuilder out, Map<String, ?> map, int indent) {
        String pad = " ".repeat(indent);
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) {
                    out.append(pad).append(key).append(": {}\n");
                } else {
                    out.append(pad).append(key).append(":\n");
                    emitMap(out, stringKeys(nested), indent + 2);
                }
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append(pad).append(key).append(": []\n");
                } else {
                    out.append(pad).append(key).append(":\n");
                    emitList(out, list, indent + 2);
                }
            } else {
                out.append(pad).append(key).append(": ").append(scalar(key, value)).append('\n');
            }
        }
    }

    private static void emitList(StringBuilder out, List<?> list, int indent) {
        String pad = " ".repeat(indent);
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                boolean first = true;
                for (Map.Entry<String, Object> entry : stringKeys(map).entrySet()) {
                    String prefix = first ? pad + "- " : pad + "  ";
                    first = false;
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                        out.append(prefix).append(entry.getKey()).append(":\n");
                        emitMap(out, stringKeys(nested), indent + 4);
                    } else if (value instanceof List<?> nested && !nested.isEmpty()) {
                        out.append(prefix).append(entry.getKey()).append(":\n");
                        emitList(out, nested, indent + 4);
                    } else {
                        out.append(prefix)
                                .append(entry.getKey())
                                .append(": ")
                                .append(scalar(entry.getKey(), value))
                                .append('\n');
                    }
                }
            } else {
                out.append(pad).append("- ").append(scalar(null, item)).append('\n');
            }
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return converted;
    }

    private static String scalar(String key, Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String text = value instanceof String string ? string : String.valueOf(value);
        if ("description".equals(key)) {
            return quote(text);
        }
        return isPlainSafe(text) ? text : quote(text);
    }

    private static boolean isPlainSafe(String text) {
        if (text.isEmpty() || !PLAIN_SAFE.matcher(text).matches()) {
            return false;
        }
        return !RESERVED.contains(text.toLowerCase(Locale.ROOT));
    }

    private static String quote(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + "\"";
    }
}
