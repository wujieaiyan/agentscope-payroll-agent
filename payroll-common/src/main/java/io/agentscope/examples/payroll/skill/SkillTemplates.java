package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置技能模板：classpath {@code skill-templates/*.md}，frontmatter 提供 id/title/description/category，
 * 正文即新建技能的初始正文。
 *
 * <p>模板正文必须能过 {@link SkillQuality} 的 error 级检查，否则「从模板创建」会直接失败；
 * 由单元测试逐篇断言，而不是等到用户点保存才发现。
 */
public final class SkillTemplates {

    public record Template(String id, String title, String description, String category, String body) {
        public int bodyLength() {
            return body.length();
        }
    }

    private static final List<String> FILES =
            List.of("payroll-assistant", "kb-qa-assistant", "policy-note", "data-reconciler");

    private final Map<String, Template> templates;

    public SkillTemplates() {
        Map<String, Template> loaded = new LinkedHashMap<>();
        for (String name : FILES) {
            Template template = load(name);
            if (template != null) {
                loaded.put(template.id(), template);
            }
        }
        this.templates = Map.copyOf(loaded);
    }

    public List<Template> list() {
        List<Template> ordered = new ArrayList<>();
        for (String name : FILES) {
            Template template = templates.get(name);
            if (template != null) {
                ordered.add(template);
            }
        }
        return List.copyOf(ordered);
    }

    public Template find(String id) {
        if (id == null) {
            return null;
        }
        return templates.get(id.trim());
    }

    public Template require(String id) {
        Template template = find(id);
        if (template == null) {
            throw new PayrollException("模板不存在：" + id);
        }
        return template;
    }

    private static Template load(String name) {
        String path = "/skill-templates/" + name + ".md";
        try (InputStream input = SkillTemplates.class.getResourceAsStream(path)) {
            if (input == null) {
                return null;
            }
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return parse(name, raw);
        } catch (IOException e) {
            throw new PayrollException("读取技能模板失败：" + name + "：" + e.getMessage());
        }
    }

    private static Template parse(String fallbackId, String raw) {
        String text = raw.replace("\r\n", "\n");
        Map<String, String> meta = new LinkedHashMap<>();
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                String front = text.substring(4, end);
                for (String line : front.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        meta.put(
                                line.substring(0, colon).trim(),
                                line.substring(colon + 1).trim());
                    }
                }
                int bodyStart = text.indexOf('\n', end + 4);
                body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
            }
        }
        String id = meta.getOrDefault("id", fallbackId);
        return new Template(
                id,
                meta.getOrDefault("title", id),
                meta.getOrDefault("description", ""),
                meta.getOrDefault("category", "通用"),
                body.strip() + "\n");
    }
}
