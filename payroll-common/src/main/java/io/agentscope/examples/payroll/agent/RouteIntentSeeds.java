package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义路由种子：类路径上的 3×30 条标注问句，随应用打包，运行期按内容版本自举索引。
 *
 * <p>版本号参与 ES 文档过滤与 ID，改动问句时同步递增，旧版本文档会在重建时清理。
 */
public final class RouteIntentSeeds {

    public static final String RESOURCE = "/routing/route-intents.json";

    /** 非薪酬语义层固定要求的三个标签；薪酬桶使用独立资源与标签集。 */
    public static final List<String> KB_LABELS =
            List.of(
                    SemanticRouteClassifier.LABEL_KB_READ,
                    SemanticRouteClassifier.LABEL_KB_ADMIN,
                    SemanticRouteClassifier.LABEL_OTHER);

    private static final ObjectMapper JSON = new ObjectMapper();

    private RouteIntentSeeds() {
    }

    public record Sample(String label, String question) {
    }

    public record Catalog(String version, List<String> labels, List<Sample> samples) {

        public Catalog {
            labels = List.copyOf(labels);
            samples = List.copyOf(samples);
        }

        public long count(String label) {
            return samples.stream().filter(sample -> sample.label().equals(label)).count();
        }
    }

    public static Catalog loadClasspath() {
        return loadClasspath(RESOURCE, KB_LABELS);
    }

    public static Catalog loadClasspath(String resource, List<String> requiredLabels) {
        try (InputStream in = RouteIntentSeeds.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少语义路由种子文件 " + resource);
            }
            return parse(in, requiredLabels);
        } catch (IOException e) {
            throw new IllegalStateException("读取语义路由种子失败：" + e.getMessage(), e);
        }
    }

    static Catalog parse(InputStream in) throws IOException {
        return parse(in, KB_LABELS);
    }

    static Catalog parse(InputStream in, List<String> requiredLabels) throws IOException {
        JsonNode root = JSON.readTree(in);
        String version = root.path("version").asText("").trim();
        if (version.isEmpty()) {
            throw new IllegalStateException("语义路由种子缺少 version");
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : root.path("labels")) {
            String value = label.asText("").trim();
            if (!value.isEmpty()) {
                labels.add(value);
            }
        }
        Set<String> known = new LinkedHashSet<>(labels);
        for (String required : requiredLabels) {
            if (!known.contains(required)) {
                throw new IllegalStateException("语义路由种子缺少标签 " + required);
            }
        }
        List<Sample> samples = new ArrayList<>();
        for (JsonNode node : root.path("samples")) {
            String label = node.path("label").asText("").trim();
            String question = node.path("question").asText("").trim();
            if (!known.contains(label)) {
                throw new IllegalStateException("语义路由种子含未知标签：" + label);
            }
            if (question.isEmpty()) {
                throw new IllegalStateException("语义路由种子含空问句（" + label + "）");
            }
            samples.add(new Sample(label, question));
        }
        for (String label : labels) {
            long count = samples.stream().filter(sample -> sample.label().equals(label)).count();
            if (count == 0) {
                throw new IllegalStateException("语义路由种子标签无样本：" + label);
            }
        }
        return new Catalog(version, labels, samples);
    }
}
