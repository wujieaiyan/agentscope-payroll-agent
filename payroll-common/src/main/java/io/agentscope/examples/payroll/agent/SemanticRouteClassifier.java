package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.memory.EmbeddingClient;
import io.agentscope.examples.payroll.memory.OllamaEmbeddingClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 语义路由：把非薪酬问题映射为 kb_read / kb_admin / other，仅决定要不要检索，不改写目标智能体。
 *
 * <p>薪酬问题不进入本类（L0 一票终止）；种子索引按内容版本与模型幂等自举。嵌入或 ES 任一异常、
 * 超时、低置信都回退 L2 模型路由，绝不阻断对话。
 */
public final class SemanticRouteClassifier implements RouteIntentClassifier, AutoCloseable {

    public static final String LABEL_KB_READ = "kb_read";
    public static final String LABEL_KB_ADMIN = "kb_admin";
    public static final String LABEL_OTHER = "other";
    public static final String LABEL_UNAVAILABLE = "unavailable";
    public static final String SOURCE_SEMANTIC = "semantic";

    private static final Logger log = LoggerFactory.getLogger(SemanticRouteClassifier.class);
    private static final int SEED_BATCH = 16;

    private final SemanticRouteSettings settings;
    private final RouteIntentSeeds.Catalog catalog;
    private final EmbeddingClient embeddings;
    private final RouteIntentStore store;
    private final boolean ready;
    private final String unavailableReason;

    private SemanticRouteClassifier(
            SemanticRouteSettings settings,
            RouteIntentSeeds.Catalog catalog,
            EmbeddingClient embeddings,
            RouteIntentStore store,
            boolean ready,
            String unavailableReason) {
        this.settings = settings == null ? SemanticRouteSettings.disabled() : settings;
        this.catalog = catalog;
        this.embeddings = embeddings;
        this.store = store;
        this.ready = ready;
        this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }

    public static SemanticRouteClassifier disabled(String reason) {
        return new SemanticRouteClassifier(
                SemanticRouteSettings.disabled(), null, null, null, false, reason);
    }

    public static SemanticRouteClassifier start(SemanticRouteSettings settings) {
        return start(
                settings,
                RouteIntentSeeds.RESOURCE,
                settings == null ? "payroll-route-intents" : settings.index(),
                RouteIntentSeeds.KB_LABELS);
    }

    /**
     * 通用入口：同一套嵌入/ES 配置可承载多个独立索引（如薪酬语义桶）。
     *
     * @param resource 种子资源路径
     * @param index ES 索引；与 KB 语义索引隔离，互不影响启停
     * @param requiredLabels 种子必须包含的标签，防止误用其它资源的种子文件
     */
    public static SemanticRouteClassifier start(
            SemanticRouteSettings settings,
            String resource,
            String index,
            List<String> requiredLabels) {
        if (settings == null || !settings.enabled()) {
            log.info("[知识库][语义] 未启用（payroll.router.semantic.enabled=false）");
            return disabled("未启用");
        }
        RouteIntentSeeds.Catalog catalog;
        try {
            catalog = RouteIntentSeeds.loadClasspath(resource, requiredLabels);
        } catch (RuntimeException e) {
            log.warn("[知识库][语义] 种子加载失败，语义路由关闭：{}", rootMessage(e));
            return disabled("种子加载失败");
        }
        EmbeddingClient queries =
                new OllamaEmbeddingClient(
                        settings.embeddingBaseUrl(),
                        settings.embeddingModel(),
                        settings.embeddingDim(),
                        Duration.ofMillis(settings.timeoutMs()));
        if (!queries.available()) {
            log.warn(
                    "[知识库][语义] 嵌入模型 {} 不可用，语义路由关闭；执行 ollama pull {} 后重启 runtime",
                    settings.embeddingModel(),
                    settings.embeddingModel());
            return disabled("嵌入模型不可用");
        }
        EmbeddingClient seeder =
                new OllamaEmbeddingClient(
                        settings.embeddingBaseUrl(),
                        settings.embeddingModel(),
                        settings.embeddingDim(),
                        Duration.ofSeconds(settings.seedTimeoutSeconds()));
        RouteIntentStore store =
                new RouteIntentStore(
                        settings.esUrl(),
                        settings.esUsername(),
                        settings.esPassword(),
                        index,
                        settings.embeddingDim());
        try {
            if (!store.ping()) {
                log.warn("[知识库][语义] Elasticsearch 不可达（{}），语义路由关闭", settings.esUrl());
                store.close();
                return disabled("ES 不可达");
            }
            store.ensureIndex();
            long expected = catalog.samples().size();
            if (store.count(catalog.version(), settings.embeddingModel()) != expected) {
                long startedAt = System.currentTimeMillis();
                seed(store, seeder, catalog, settings);
                log.info(
                        "[知识库][语义] 已重建意图索引 {}：版本 {}，模型 {}，样本 {} 条，耗时 {}ms",
                        index,
                        catalog.version(),
                        settings.embeddingModel(),
                        expected,
                        System.currentTimeMillis() - startedAt);
            }
            log.info(
                    "[知识库][语义] 已启用：index={}，model={}，minScore={}，margin={}，版本={}",
                    index,
                    settings.embeddingModel(),
                    settings.minScore(),
                    settings.margin(),
                    catalog.version());
            return new SemanticRouteClassifier(settings, catalog, queries, store, true, "");
        } catch (RuntimeException e) {
            log.warn("[知识库][语义] 初始化失败，语义路由关闭：{}", rootMessage(e));
            store.close();
            return disabled(rootMessage(e));
        }
    }

    public boolean ready() {
        return ready;
    }

    public Decision classify(String question) {
        if (!ready) {
            return Decision.unavailable(unavailableReason);
        }
        if (question == null || question.isBlank()) {
            return Decision.unavailable("问题为空");
        }
        long startedAt = System.currentTimeMillis();
        try {
            float[] vector = embeddings.embed(question.trim());
            List<Hit> hits =
                    store.top(
                            catalog.version(),
                            settings.embeddingModel(),
                            vector,
                            catalog.samples().size());
            Decision decision = decide(hits, settings.minScore(), settings.margin());
            log.info(
                    "[知识库][语义] label={}，score={}，margin={}，confident={}，耗时={}ms，question={}",
                    decision.label(),
                    String.format("%.3f", decision.score()),
                    String.format("%.3f", decision.margin()),
                    decision.confident(),
                    System.currentTimeMillis() - startedAt,
                    preview(question, 80));
            return decision;
        } catch (RuntimeException e) {
            log.warn("[知识库][语义] 判定失败，回退模型路由：{}", rootMessage(e));
            return Decision.unavailable(rootMessage(e));
        }
    }

    /** 按标签取最高分，再比较 top1 与 top2 标签；低置信不采用，交回 L2。 */
    public static Decision decide(List<Hit> hits, double minScore, double minMargin) {
        if (hits == null || hits.isEmpty()) {
            return Decision.unavailable("无种子命中");
        }
        Map<String, Double> best = new HashMap<>();
        for (Hit hit : hits) {
            if (hit == null || hit.label() == null || hit.label().isBlank()) {
                continue;
            }
            best.merge(hit.label(), hit.score(), Math::max);
        }
        if (best.isEmpty()) {
            return Decision.unavailable("无种子命中");
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(best.entrySet());
        ranked.sort((left, right) -> Double.compare(right.getValue(), left.getValue()));
        double top = ranked.get(0).getValue();
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0D;
        double margin = top - second;
        boolean confident = top >= minScore && margin >= minMargin;
        return new Decision(
                confident,
                ranked.get(0).getKey(),
                top,
                margin,
                confident ? "" : "低置信");
    }

    private static void seed(
            RouteIntentStore store,
            EmbeddingClient seeder,
            RouteIntentSeeds.Catalog catalog,
            SemanticRouteSettings settings) {
        List<RouteIntentSeeds.Sample> samples = catalog.samples();
        List<float[]> vectors = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i += SEED_BATCH) {
            int end = Math.min(i + SEED_BATCH, samples.size());
            List<String> texts = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                texts.add(samples.get(j).question());
            }
            vectors.addAll(seeder.embedAll(texts));
        }
        store.replace(catalog.version(), settings.embeddingModel(), samples, vectors);
    }

    private static String preview(String text, int limit) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= limit ? flattened : flattened.substring(0, limit) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        if (store != null) {
            store.close();
        }
    }

    public record Hit(String label, double score) {
    }

    public record Decision(
            boolean confident, String label, double score, double margin, String reason) {

        public Decision {
            label = label == null || label.isBlank() ? LABEL_UNAVAILABLE : label;
            reason = reason == null ? "" : reason;
        }

        public static Decision unavailable(String reason) {
            return new Decision(false, LABEL_UNAVAILABLE, 0D, 0D, reason);
        }
    }
}
