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
        try (InputStream in = RouteIntentSeeds.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少语义路由种子文件 " + RESOURCE);
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取语义路由种子失败：" + e.getMessage(), e);
        }
    }

    static Catalog parse(InputStream in) throws IOException {
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
        for (String required :
                List.of(
                        SemanticRouteClassifier.LABEL_KB_READ,
                        SemanticRouteClassifier.LABEL_KB_ADMIN,
                        SemanticRouteClassifier.LABEL_OTHER)) {
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
